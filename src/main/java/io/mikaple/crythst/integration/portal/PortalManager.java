package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.integration.fabric.portal.PortalCommand;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                            record.dimension(), record.shape(), record.linkedId(), record.anchor());
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
                        portal.plane(), portal.linkedPortalId(), portal.shape().outline(),
                        portal.anchor()));
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
            // An attached portal keeps riding its carrier: translate the new
            // world plane into the carrier's local space at the pose it has
            // right now, so the next sample reproduces this plane.
            final PhysicsStructure carrier = carrierStructure(existing);
            final PortalAnchor anchor = existing.anchor() == null || carrier == null
                    ? existing.anchor()
                    : PortalAnchor.at(carrier.body().id(), newPlane, carrier.pose());
            final Portal moved = new Portal(existing.id(), existing.name(),
                    existing.dimension(), existing.shape().withPlane(newPlane),
                    existing.linkedPortalId(), anchor);
            BY_NAME.put(moved.name(), moved);
            BY_ID.put(moved.id(), moved);
            revision++;
            markDirty();
            return moved;
        }
    }

    /**
     * Binds a portal to a moving physics structure without moving it.
     *
     * <p>The plane the portal occupies right now becomes the anchor's local
     * plane, so the opening does not jump on attach. From then on the world
     * plane follows {@code carrier.pose()} — including rotation.</p>
     *
     * @return the updated portal, or {@code null} when no portal has that name
     */
    public static Portal attach(String name, PhysicsStructure carrier) {
        Objects.requireNonNull(carrier, "carrier");
        synchronized (LOCK) {
            final Portal existing = BY_NAME.get(name);
            if (existing == null) {
                return null;
            }
            final PortalPlane worldPlane = planeOf(existing);
            final PortalAnchor anchor =
                    PortalAnchor.at(carrier.body().id(), worldPlane, carrier.pose());
            final Portal attached = new Portal(existing.id(), existing.name(),
                    existing.dimension(), existing.shape().withPlane(worldPlane),
                    existing.linkedPortalId(), anchor);
            BY_NAME.put(attached.name(), attached);
            BY_ID.put(attached.id(), attached);
            revision++;
            markDirty();
            return attached;
        }
    }

    /**
     * Freezes an attached portal at its current world plane and drops the
     * carrier binding.
     *
     * @return the updated portal, or {@code null} when no portal has that name
     */
    public static Portal detach(String name) {
        synchronized (LOCK) {
            final Portal existing = BY_NAME.get(name);
            if (existing == null) {
                return null;
            }
            final PortalPlane frozen = planeOf(existing);
            final Portal detached = new Portal(existing.id(), existing.name(),
                    existing.dimension(), existing.shape().withPlane(frozen),
                    existing.linkedPortalId(), (PortalAnchor) null);
            BY_NAME.put(detached.name(), detached);
            BY_ID.put(detached.id(), detached);
            revision++;
            markDirty();
            return detached;
        }
    }

    /**
     * The world plane of a portal right now.
     *
     * <p>For a static portal this is {@link Portal#plane()}. For an attached
     * portal it is sampled from the carrier pose, and the stored plane is used
     * only while the carrier is not loaded.</p>
     */
    public static PortalPlane planeOf(Portal portal) {
        if (portal == null) {
            return null;
        }
        final PortalAnchor anchor = portal.anchor();
        if (anchor == null) {
            return portal.plane();
        }
        final PhysicsStructure carrier = carrierStructure(portal);
        return carrier == null ? portal.plane() : anchor.worldPlane(carrier.pose());
    }

    /** The live opening of a portal: its outline re-expressed on the live plane. */
    public static PortalShape shapeOf(Portal portal) {
        if (portal == null) {
            return null;
        }
        return portal.shape().withPlane(planeOf(portal));
    }

    /**
     * The structure carrying this portal, or {@code null} for a static portal
     * and for an attached portal whose carrier is not currently simulated.
     */
    public static PhysicsStructure carrierStructure(Portal portal) {
        if (portal == null) {
            return null;
        }
        final PortalAnchor anchor = portal.anchor();
        if (anchor == null) {
            return null;
        }
        final LevelPhysics physics = physicsFor(portal.dimension());
        if (physics == null) {
            return null;
        }
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.body().id() == anchor.carrierBodyId()) {
                return structure;
            }
        }
        return null;
    }

    /**
     * World-space velocity of the portal's carrier material point at
     * {@code worldPoint}, or a zero vector for a static portal.
     *
     * <p>The teleporter subtracts the entry carrier velocity and adds the exit
     * carrier velocity so an entity that steps off a moving platform arrives
     * with the destination platform's motion instead of being flung.</p>
     */
    public static Vector3d carrierVelocity(Portal portal, Vector3d worldPoint) {
        final Vector3d result = new Vector3d();
        final PhysicsStructure carrier = carrierStructure(portal);
        if (carrier == null) {
            return result;
        }
        final BodyHandle body = carrier.body();
        result.set(body.linearVelocity());
        final Vector3d angular = body.angularVelocity();
        if (angular.lengthSquared() > 1.0e-9) {
            final Vector3d offset = new Vector3d(worldPoint).sub(body.transform().position());
            result.add(new Vector3d(angular).cross(offset));
        }
        return result;
    }

    private static LevelPhysics physicsFor(ResourceKey<Level> dimension) {
        final MinecraftServer server = activeServer;
        if (server == null || dimension == null) {
            return null;
        }
        final ServerLevel level = server.getLevel(dimension);
        return level == null ? null : MinecraftPhysics.getIfPresent(level);
    }

    /**
     * Finds the structure whose simulated origin is closest to {@code position}.
     *
     * <p>Used by {@code /crythst portal attach} when no body id is given. The
     * comparison uses the structure body origin, not its cell bounds, which is
     * good enough for a command helper.</p>
     *
     * @return the closest structure within {@code maxDistance}, or {@code null}
     */
    public static PhysicsStructure nearestStructure(ResourceKey<Level> dimension,
                                                     Vector3d position, double maxDistance) {
        final LevelPhysics physics = physicsFor(dimension);
        if (physics == null || position == null) {
            return null;
        }
        PhysicsStructure best = null;
        double bestSquared = maxDistance <= 0.0 ? Double.MAX_VALUE : maxDistance * maxDistance;
        for (PhysicsStructure structure : physics.structures()) {
            final double distance = structure.pose().position().distanceSquared(position);
            if (distance <= bestSquared) {
                bestSquared = distance;
                best = structure;
            }
        }
        return best;
    }

    /** The structure with this body id in {@code dimension}, or {@code null}. */
    public static PhysicsStructure structureByBody(ResourceKey<Level> dimension, long bodyId) {
        final LevelPhysics physics = physicsFor(dimension);
        if (physics == null) {
            return null;
        }
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.body().id() == bodyId) {
                return structure;
            }
        }
        return null;
    }

    /** Moves a portal and replaces its opening shape. */
    public static Portal reshape(String name, PortalShape newShape) {
        synchronized (LOCK) {
            final Portal existing = BY_NAME.get(name);
            if (existing == null) {
                return null;
            }
            final Portal moved = new Portal(existing.id(), existing.name(),
                    existing.dimension(), newShape, existing.linkedPortalId(), existing.anchor());
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
                result.add(resolvedSnapshot(portal, BY_ID.get(portal.linkedPortalId())));
            }
            return List.copyOf(result);
        }
    }

    public static List<PortalSnapshot> snapshotsIn(ResourceKey<Level> dimension) {
        synchronized (LOCK) {
            final List<PortalSnapshot> result = new ArrayList<>();
            for (Portal portal : BY_NAME.values()) {
                if (portal.dimension().equals(dimension)) {
                    result.add(resolvedSnapshot(portal, BY_ID.get(portal.linkedPortalId())));
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * Snapshot with live planes for attached portals. The anchors are kept so
     * the client can keep re-sampling them at frame rate; only the planes are
     * resolved here for the very first frame after a sync.
     */
    private static PortalSnapshot resolvedSnapshot(Portal portal, Portal destination) {
        final PortalSnapshot raw = PortalSnapshot.of(portal, destination);
        if (!raw.moving()) {
            return raw;
        }
        return raw.withPlanes(
                planeOf(portal), shapeOf(portal),
                destination == null ? null : planeOf(destination),
                destination == null ? null : shapeOf(destination));
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
