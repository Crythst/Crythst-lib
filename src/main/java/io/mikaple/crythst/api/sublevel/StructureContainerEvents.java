package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.Crythst;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registration and dispatch for structure-container lifecycle callbacks.
 *
 * <p>This is intentionally separate from {@code CrythstEvents}: the older bus
 * exposes a level/pipeline ready event, while this class exposes the actual
 * {@link StructureContainer}. A ready callback runs once per physics world,
 * when {@code MinecraftPhysics} creates it. Observers registered here are
 * attached to every server container created after registration and to all
 * containers that already exist.</p>
 *
 * <p>All callbacks run on the server thread.</p>
 */
public final class StructureContainerEvents {
    private static final List<Consumer<StructureContainer>> READY = new CopyOnWriteArrayList<>();
    private static final List<StructureObserver> OBSERVERS = new CopyOnWriteArrayList<>();

    private StructureContainerEvents() {
    }

    /**
     * Registers a callback for every server container created from now on.
     *
     * @return a handle that removes the callback when closed
     */
    public static AutoCloseable registerContainerReady(Consumer<StructureContainer> listener) {
        Objects.requireNonNull(listener, "listener");
        READY.add(listener);
        return () -> READY.remove(listener);
    }

    /**
     * Registers an observer for every server container, including containers
     * that already exist.
     *
     * @return a handle that detaches the observer when closed
     */
    public static AutoCloseable registerObserver(StructureObserver observer) {
        Objects.requireNonNull(observer, "observer");
        if (!OBSERVERS.contains(observer)) {
            OBSERVERS.add(observer);
        }
        for (ServerStructureContainer container : ServerStructureContainer.all()) {
            container.addObserver(observer);
        }
        return () -> {
            OBSERVERS.remove(observer);
            for (ServerStructureContainer container : ServerStructureContainer.all()) {
                container.removeObserver(observer);
            }
        };
    }

    /**
     * Internal dispatch point used when a physics world (and therefore its
     * container) is created.
     */
    public static void fireContainerReady(StructureContainer container) {
        Objects.requireNonNull(container, "container");
        for (Consumer<StructureContainer> listener : READY) {
            try {
                listener.accept(container);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure-container-ready listener failed", t);
            }
        }
        if (container instanceof ServerStructureContainer serverContainer) {
            for (StructureObserver observer : OBSERVERS) {
                serverContainer.addObserver(observer);
            }
        }
    }

    /**
     * Diffs all live container observers. Called by the server physics tick.
     */
    public static void tick() {
        ServerStructureContainer.tickAll();
    }

    /** Number of registered ready listeners. Primarily diagnostic. */
    public static int readyListenerCount() {
        return READY.size();
    }

    /** Number of globally registered observers. Primarily diagnostic. */
    public static int observerCount() {
        return OBSERVERS.size();
    }

    /**
     * Removes all ready listeners and global observers. Intended for tests and
     * process shutdown; container-local observers are left untouched.
     */
    public static void clear() {
        READY.clear();
        OBSERVERS.clear();
    }
}
