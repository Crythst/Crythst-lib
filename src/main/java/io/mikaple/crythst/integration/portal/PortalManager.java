package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.integration.fabric.portal.PortalCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

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
 *     <li>server-stopped cleanup, including cooldown maps.</li>
 * </ul>
 *
 * <p>Persistence, networking and cross-dimension physics are later
 * milestones. See {@code docs/immersive-portal.md}.</p>
 */
public final class PortalManager {
    private static final Object LOCK = new Object();
    private static final Map<String, Portal> BY_NAME = new LinkedHashMap<>();
    private static final Map<UUID, Portal> BY_ID = new LinkedHashMap<>();

    private static volatile boolean registered;

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
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            clearAll();
            PortalTeleporter.clearCooldowns();
        });
        io.mikaple.crythst.Crythst.LOGGER.info(
                "Crythst static portals registered (commands, teleport tick, cleanup)");
    }

    /**
     * Creates and registers an unlinked portal.
     *
     * @throws IllegalArgumentException when the name is already in use
     */
    public static Portal create(ResourceKey<Level> dimension, String name, PortalPlane plane) {
        synchronized (LOCK) {
            if (BY_NAME.containsKey(name)) {
                throw new IllegalArgumentException("A portal named '" + name + "' already exists");
            }
            final Portal portal = new Portal(UUID.randomUUID(), name, dimension, plane);
            BY_NAME.put(portal.name(), portal);
            BY_ID.put(portal.id(), portal);
            return portal;
        }
    }

    /**
     * Links two portals bidirectionally, removing any previous links either
     * portal may have had.
     *
     * @return the link transform from {@code firstName} to {@code secondName},
     *         or {@code null} when a name is unknown or both names are equal
     */
    public static PortalTransform link(String firstName, String secondName) {
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
            return PortalTransform.between(first.plane(), second.plane());
        }
    }

    /** Removes a portal and clears any link pointing at it. */
    public static Portal remove(String name) {
        synchronized (LOCK) {
            final Portal removed = BY_NAME.remove(name);
            if (removed == null) {
                return null;
            }
            BY_ID.remove(removed.id());
            unlinkBackReferences(removed);
            return removed;
        }
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
                result.add(portal.snapshot());
            }
            return List.copyOf(result);
        }
    }

    public static List<PortalSnapshot> snapshotsIn(ResourceKey<Level> dimension) {
        synchronized (LOCK) {
            final List<PortalSnapshot> result = new ArrayList<>();
            for (Portal portal : BY_NAME.values()) {
                if (portal.dimension().equals(dimension)) {
                    result.add(portal.snapshot());
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
        }
    }

    private static void unlinkBackReferences(Portal target) {
        for (Portal portal : BY_NAME.values()) {
            if (target.id().equals(portal.linkedPortalId())) {
                portal.unlink();
            }
        }
    }
}
