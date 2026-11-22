package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.integration.fabric.portal.PortalCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side registry of static portals.
 *
 * <p>The registry is a process-wide map because an integrated server and a
 * dedicated server both have exactly one logical server per JVM. Every
 * mutation is synchronised; readers receive immutable {@link PortalSnapshot}s,
 * so the client tick can ask for a snapshot without racing a command.</p>
 *
 * <p>{@link #register()} wires the whole milestone into Fabric:</p>
 * <ul>
 *     <li>the {@code /crythst portal ...} command tree,</li>
 *     <li>the end-of-server-tick teleport pass (after physics),</li>
 *     <li>server-start loading and save-time persistence through
 *     {@link PortalSavedData},</li>
 *     <li>server-stopped cleanup, including cooldown maps.</li>
 * </ul>
 *
 * <p>Persistence stores stable UUIDs, names, dimensions, planes and links in
 * the overworld's saved data. Client capture textures are never persisted.</p>
 */
public final class PortalManager {
    private static final Object LOCK = new Object();
    private static final Map<String, Portal> BY_NAME = new LinkedHashMap<>();
    private static final Map<UUID, Portal> BY_ID = new LinkedHashMap<>();

    private static volatile boolean registered;
    private static MinecraftServer activeServer;
    private static PortalSavedData savedData;
    private static volatile long revision;

    private PortalManager() {
    }

    /**
     * Registers the server tick hook, lifecycle cleanup and the portal
     * commands. Safe to call more than once.
     *
     * <p>Call this from {@code CrythstFabric#onInitialize()} <em>after</em>
     * {@code PhysicsTicker.register()} so teleportation sees the pose produced
     * by the current physics tick.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        CommandRegistrationCallback.EVENT.register(PortalCommand::register);
        ServerTickEvents.END_SERVER_TICK.register(PortalTeleporter::tick);
        io.mikaple.crythst.integration.fabric.portal.PortalSyncServer.register();
        ServerLifecycleEvents.SERVER_STARTED.register(PortalManager::onServerStarted);
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> markDirty());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> markDirty());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            clearAll();
            savedData = null;
            activeServer = null;
            PortalTeleporter.clearCooldowns();
        });
        io.mikaple.crythst.Crythst.LOGGER.info(
                "Crythst static portals registered (commands, persistence, teleport tick, cleanup)");
    }

    private static void onServerStarted(MinecraftServer server) {
        activeServer = server;
        try {
            final SavedDataStorage storage = server.overworld().getDataStorage();
            savedData = storage.computeIfAbsent(PortalSavedData.TYPE);
            loadFromSaved(savedData.readPortals());
            markDirty();
        } catch (Throwable error) {
            savedData = null;
            clearAll();
            io.mikaple.crythst.Crythst.LOGGER.error(
                    "Could not load Crythst portal data; portals will not persist this session", error);
        }
    }

    static void loadFromSaved(List<PortalSavedData.StoredPortal> stored) {
        final int loaded;
        synchronized (LOCK) {
            BY_NAME.clear();
            BY_ID.clear();
            int count = 0;
            for (PortalSavedData.StoredPortal record : stored) {
                if (BY_ID.containsKey(record.id()) || BY_NAME.containsKey(record.name())) {
                    io.mikaple.crythst.Crythst.LOGGER.warn(
                            "Skipping duplicate saved portal '{}' ({})", record.name(), record.id());
                    continue;
                }
                try {
                    final Portal portal = new Portal(record.id(), record.name(),
                            record.dimension(), record.shape(), record.linkedId());
                    BY_NAME.put(portal.name(), portal);
                    BY_ID.put(portal.id(), portal);
                    count++;
                } catch (IllegalArgumentException error) {
                    io.mikaple.crythst.Crythst.LOGGER.warn(
                            "Skipping invalid saved portal '{}'", record.name(), error);
                }
            }
            repairLinks();
            loaded = count;
            revision++;
        }
        io.mikaple.crythst.Crythst.LOGGER.info("Loaded {} persisted Crythst portal(s)", loaded);
    }

    /**
     * Drops links whose target no longer exists and repairs one-sided links
     * when the target is free.
     */
    private static void repairLinks() {
        final List<Portal> portals = new ArrayList<>(BY_NAME.values());
        for (Portal portal : portals) {
            final UUID linkedId = portal.linkedPortalId();
            if (linkedId == null) {
                continue;
            }
            final Portal target = BY_ID.get(linkedId);
            if (target == null) {
                portal.unlink();
                continue;
            }
            if (target.linkedPortalId() == null) {
                target.linkTo(portal.id());
            } else if (!target.linkedPortalId().equals(portal.id())) {
                portal.unlink();
            }
        }
    }

    private static List<PortalSavedData.StoredPortal> buildStoredPortals() {
        synchronized (LOCK) {
            final List<PortalSavedData.StoredPortal> records = new ArrayList<>(BY_NAME.size());
            for (Portal portal : BY_NAME.values()) {
                records.add(new PortalSavedData.StoredPortal(
                        portal.id(), portal.name(), portal.dimension(),
                        portal.plane(), portal.linkedPortalId(), portal.shape().outline()));
            }
            return records;
        }
    }

    /** Writes the current registry into the saved data and marks it dirty. */
    public static void markDirty() {
        final PortalSavedData data = savedData;
        if (data == null || activeServer == null) {
            return;
        }
        data.setTag(PortalSavedData.write(buildStoredPortals()));
    }

    /**
     * Creates and registers an unlinked portal.
     *
     * @throws IllegalArgumentException when the name is already in use
     */
    public static Portal create(ResourceKey<Level> dimension, String name, PortalPlane plane) {
        return create(dimension, name, PortalShape.rectangle(plane));
    }

    /**
     * Creates and registers an unlinked portal with an arbitrary shape.
     *
     * @throws IllegalArgumentException when the name is already in use
     */
    public static Portal create(ResourceKey<Level> dimension, String name, PortalShape shape) {
        // Portal trims names. Normalize before the uniqueness check, otherwise
        // "gate " would pass the check and then overwrite the existing "gate"
        // entry in BY_NAME while leaving its UUID orphaned in BY_ID.
        final String normalizedName = name == null ? "" : name.trim();
        final Portal portal;
        synchronized (LOCK) {
            if (BY_NAME.containsKey(normalizedName)) {
                throw new IllegalArgumentException(
                        "A portal named '" + normalizedName + "' already exists");
            }
            portal = new Portal(UUID.randomUUID(), normalizedName, dimension, shape, null);
            BY_NAME.put(portal.name(), portal);
            BY_ID.put(portal.id(), portal);
            revision++;
        }
        markDirty();
        return portal;
    }

    /**
     * Links two portals bidirectionally, removing any previous links either
     * portal may have had.
     *
     * @return the link transform from {@code firstName} to {@code secondName},
     *         or {@code null} when a name is unknown or both names are equal
     */
    public static PortalTransform link(String firstName, String secondName) {
        final PortalTransform transform;
        synchronized (LOCK) {
            if (firstName.equals(secondName)) {
                return null;
            }
            final Portal first = BY_NAME.get(firstName);
            final Portal second = BY_NAME.get(secondName);
            if (first == null || second == null) {
                return null;
            }

            // Drop old back-references so no portal keeps a dangling link.
            unlinkBackReferences(first);
            unlinkBackReferences(second);

            first.linkTo(second.id());
            second.linkTo(first.id());
            transform = PortalTransform.between(first.plane(), second.plane());
            revision++;
        }
        markDirty();
        return transform;
    }

    /**
     * Moves/reorients an existing portal without changing its UUID, name,
     * dimension or link. The shape outline is kept and re-expressed on the new
     * plane; use {@link #reshape} to replace the opening as well.
     *
     * <p>Intended for a moving portal carrier (a physics structure or a script)
     * to publish a new pose. The server sync revision changes, so clients
     * recapture their view texture on the next frame.</p>
     */
    public static Portal move(String name, PortalPlane newPlane) {
        synchronized (LOCK) {
            final Portal existing = BY_NAME.get(name);
            if (existing == null) {
                return null;
            }
            final Portal moved = new Portal(existing.id(), existing.name(),
                    existing.dimension(), existing.shape().withPlane(newPlane),
                    existing.linkedPortalId());
            BY_NAME.put(moved.name(), moved);
            BY_ID.put(moved.id(), moved);
            revision++;
            markDirty();
            return moved;
        }
    }

    /** Moves a portal and replaces its opening shape. */
    public static Portal reshape(String name, PortalShape newShape) {
        synchronized (LOCK) {
            final Portal existing = BY_NAME.get(name);
            if (existing == null) {
                return null;
            }
            final Portal moved = new Portal(existing.id(), existing.name(),
                    existing.dimension(), newShape, existing.linkedPortalId());
            BY_NAME.put(moved.name(), moved);
            BY_ID.put(moved.id(), moved);
            revision++;
            markDirty();
            return moved;
        }
    }

    /** Removes a portal and clears any link pointing at it. */
    public static Portal remove(String name) {        final Portal removed;
        synchronized (LOCK) {
            removed = BY_NAME.remove(name);
            if (removed == null) {
                return null;
            }
            BY_ID.remove(removed.id());
            unlinkBackReferences(removed);
            revision++;
        }
        markDirty();
        return removed;
    }

    public static Portal findByName(String name) {
        synchronized (LOCK) {
            return BY_NAME.get(name);
        }
    }

    public static Portal findById(UUID id) {
        if (id == null) {
            return null;
        }
        synchronized (LOCK) {
            return BY_ID.get(id);
        }
    }

    /** The portal linked to {@code portal}, or {@code null}. */
    public static Portal linkedPortal(Portal portal) {
        if (portal == null) {
            return null;
        }
        return findById(portal.linkedPortalId());
    }

    /** Immutable list of every portal, in creation order. */
    public static List<Portal> all() {
        synchronized (LOCK) {
            return List.copyOf(BY_NAME.values());
        }
    }

    public static List<Portal> portalsIn(ResourceKey<Level> dimension) {
        synchronized (LOCK) {
            final List<Portal> result = new ArrayList<>();
            for (Portal portal : BY_NAME.values()) {
                if (portal.dimension().equals(dimension)) {
                    result.add(portal);
                }
            }
            return List.copyOf(result);
        }
    }

    /** Immutable snapshots of every portal, safe for any thread. */
    public static List<PortalSnapshot> snapshots() {
        synchronized (LOCK) {
            final List<PortalSnapshot> result = new ArrayList<>(BY_NAME.size());
            for (Portal portal : BY_NAME.values()) {
                result.add(PortalSnapshot.of(portal, BY_ID.get(portal.linkedPortalId())));
            }
            return List.copyOf(result);
        }
    }

    public static List<PortalSnapshot> snapshotsIn(ResourceKey<Level> dimension) {
        synchronized (LOCK) {
            final List<PortalSnapshot> result = new ArrayList<>();
            for (Portal portal : BY_NAME.values()) {
                if (portal.dimension().equals(dimension)) {
                    result.add(PortalSnapshot.of(portal, BY_ID.get(portal.linkedPortalId())));
                }
            }
            return List.copyOf(result);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return BY_NAME.size();
        }
    }

    public static void clearAll() {
        synchronized (LOCK) {
            BY_NAME.clear();
            BY_ID.clear();
            revision++;
        }
    }

    /** Monotonic registry revision; used by the server sync broadcaster. */
    public static long revision() {
        return revision;
    }

    private static void unlinkBackReferences(Portal target) {
        for (Portal portal : BY_NAME.values()) {
            if (target.id().equals(portal.linkedPortalId())) {
                portal.unlink();
            }
        }
    }
}
