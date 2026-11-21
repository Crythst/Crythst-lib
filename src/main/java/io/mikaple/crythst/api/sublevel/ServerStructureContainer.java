package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Authoritative structure container for one server level.
 *
 * <p>This is a thin, stable facade over a {@link LevelPhysics}. Creation,
 * removal and refresh still happen in the physics world; the container adds
 * identity lookup and a polling observer layer. The observer layer diffs the
 * authoritative structure list in {@link #tick()}, which
 * {@link StructureContainerEvents} calls once per server tick.</p>
 *
 * <p>All methods must be called on the server thread.</p>
 */
public final class ServerStructureContainer implements StructureContainer {
    private static final ConcurrentHashMap<LevelPhysics, ServerStructureContainer> CONTAINERS =
            new ConcurrentHashMap<>();

    private final LevelPhysics physics;
    private final List<StructureObserver> observers = new CopyOnWriteArrayList<>();
    private List<PhysicsStructure> lastSeen;
    private boolean closed;

    private ServerStructureContainer(LevelPhysics physics) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.lastSeen = physics.structures();
    }

    /**
     * Returns the stable container for a physics world, creating it on first
     * use.
     */
    public static ServerStructureContainer of(LevelPhysics physics) {
        Objects.requireNonNull(physics, "physics");
        return CONTAINERS.computeIfAbsent(physics, ServerStructureContainer::new);
    }

    /**
     * Returns the container for a physics world if one has already been
     * created, without creating a new one.
     */
    public static ServerStructureContainer getIfPresent(LevelPhysics physics) {
        return physics == null ? null : CONTAINERS.get(physics);
    }

    /**
     * Snapshot of the currently registered containers.
     */
    static List<ServerStructureContainer> all() {
        return new ArrayList<>(CONTAINERS.values());
    }

    /**
     * Diffs every live server container. Called once per server tick.
     */
    public static void tickAll() {
        for (ServerStructureContainer container : CONTAINERS.values()) {
            container.tick();
        }
    }

    /**
     * Closes the container associated with a physics world after the world has
     * shut down. Pending removals are reported before observers are detached.
     */
    public static void closeFor(LevelPhysics physics) {
        if (physics == null) {
            return;
        }
        final ServerStructureContainer container = CONTAINERS.remove(physics);
        if (container != null) {
            container.close();
        }
    }

    /**
     * Closes all containers. Used when the whole physics runtime shuts down.
     */
    public static void closeAll() {
        final List<ServerStructureContainer> current = new ArrayList<>(CONTAINERS.values());
        CONTAINERS.clear();
        for (ServerStructureContainer container : current) {
            container.close();
        }
    }

    /** Underlying physics world. */
    public LevelPhysics physics() {
        return physics;
    }

    @Override
    public ServerLevel level() {
        return physics.level();
    }

    @Override
    public List<PhysicsStructure> structures() {
        return closed ? List.of() : physics.structures();
    }

    /**
     * Creates a structure through the physics world and reports it to
     * observers.
     */
    public PhysicsStructure createStructure(StructureDef definition) {
        Objects.requireNonNull(definition, "definition");
        final PhysicsStructure structure = physics.createStructure(definition);
        poll();
        return structure;
    }

    /**
     * Removes a structure through the physics world and reports the removal to
     * observers.
     */
    public void removeStructure(PhysicsStructure structure) {
        Objects.requireNonNull(structure, "structure");
        physics.removeStructure(structure);
        poll();
    }

    /**
     * Refreshes a structure through the physics world.
     *
     * <p>If the refresh splits the body, the original removal and the new
     * bodies' additions are reported by the diff. If it stayed one body,
     * {@link StructureObserver#onStructureRefreshed} is called for it.</p>
     *
     * @return the bodies that now represent the structure, as returned by
     *         {@link LevelPhysics#refreshStructure(PhysicsStructure)}
     */
    public List<PhysicsStructure> refreshStructure(PhysicsStructure structure) {
        Objects.requireNonNull(structure, "structure");
        final List<PhysicsStructure> result = physics.refreshStructure(structure);
        final boolean remained = containsIdentity(physics.structures(), structure);
        poll();
        if (remained) {
            notifyRefreshed(structure);
        }
        return result;
    }

    /**
     * Registers an observer for this container.
     *
     * <p>Existing structures are not replayed; observers should read
     * {@link #structures()} once after registering if they need the current
     * set. The returned handle removes only its own observer.</p>
     */
    public synchronized AutoCloseable addObserver(StructureObserver observer) {
        Objects.requireNonNull(observer, "observer");
        if (closed) {
            return () -> {
            };
        }
        // Advance the diff first so changes that happened before registration
        // are not replayed to the new observer.
        poll();
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
        return () -> removeObserver(observer);
    }

    /** Removes a previously registered observer. */
    public synchronized boolean removeObserver(StructureObserver observer) {
        return observers.remove(observer);
    }

    /** Number of registered observers. Primarily diagnostic. */
    public int observerCount() {
        return observers.size();
    }

    /**
     * Reports structure-list changes since the previous tick. Safe to call more
     * often than once per tick; unchanged lists are a no-op.
     */
    public synchronized void tick() {
        poll();
    }

    /**
     * Detaches observers and removes this container from the registry.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        // Report removals caused by the physics world shutting down before
        // observers are detached.
        poll();
        closed = true;
        observers.clear();
        lastSeen = List.of();
        CONTAINERS.remove(physics, this);
    }

    private synchronized void poll() {
        if (closed) {
            return;
        }
        final List<PhysicsStructure> current = physics.structures();
        if (StructureObserverSupport.sameIdentities(lastSeen, current)) {
            return;
        }
        for (StructureObserver observer : observers) {
            try {
                StructureObserverSupport.diff(lastSeen, current, observer);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure observer failed while diffing {}", level().dimension(), t);
            }
        }
        lastSeen = current;
    }

    private void notifyRefreshed(PhysicsStructure structure) {
        for (StructureObserver observer : observers) {
            try {
                observer.onStructureRefreshed(structure);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure observer failed while refreshing {}", level().dimension(), t);
            }
        }
    }

    private static boolean containsIdentity(List<PhysicsStructure> structures, PhysicsStructure target) {
        for (PhysicsStructure structure : structures) {
            if (structure == target) {
                return true;
            }
        }
        return false;
    }
}
