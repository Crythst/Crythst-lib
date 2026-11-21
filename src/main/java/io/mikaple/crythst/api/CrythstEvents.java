package io.mikaple.crythst.api;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.event.PostPhysicsTick;
import io.mikaple.crythst.api.event.PrePhysicsTick;
import io.mikaple.crythst.api.event.PhysicsTickEvent;
import io.mikaple.crythst.api.event.StructureContainerReady;
import io.mikaple.crythst.api.event.StructureContainerReadyEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe event bus for the Crythst public API.
 *
 * <p>Listeners are stored in registration order. A failing listener is logged
 * and does not prevent later listeners from running. Registration and removal
 * are safe from any thread; callbacks themselves run on the server thread.</p>
 *
 * <p>The bus deliberately does not depend on Fabric's event API so the core
 * physics module remains loadable without the Fabric event package.</p>
 */
public final class CrythstEvents {
    private static final List<PrePhysicsTick> PRE_TICK = new CopyOnWriteArrayList<>();
    private static final List<PostPhysicsTick> POST_TICK = new CopyOnWriteArrayList<>();
    private static final List<StructureContainerReady> READY = new CopyOnWriteArrayList<>();

    private CrythstEvents() {
    }

    /**
     * Registers a callback for every fixed physics substep.
     *
     * @return a handle that removes the listener when closed
     */
    public static AutoCloseable registerPrePhysicsTick(PrePhysicsTick listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        PRE_TICK.add(listener);
        return () -> PRE_TICK.remove(listener);
    }

    /**
     * Registers a callback for after every fixed physics substep.
     *
     * @return a handle that removes the listener when closed
     */
    public static AutoCloseable registerPostPhysicsTick(PostPhysicsTick listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        POST_TICK.add(listener);
        return () -> POST_TICK.remove(listener);
    }

    /**
     * Registers a callback for level physics-container creation.
     *
     * @return a handle that removes the listener when closed
     */
    public static AutoCloseable registerStructureContainerReady(StructureContainerReady listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        READY.add(listener);
        return () -> READY.remove(listener);
    }

    /** Removes a previously registered pre-step listener. */
    public static void unregisterPrePhysicsTick(PrePhysicsTick listener) {
        PRE_TICK.remove(listener);
    }

    /** Removes a previously registered post-step listener. */
    public static void unregisterPostPhysicsTick(PostPhysicsTick listener) {
        POST_TICK.remove(listener);
    }

    /** Removes a previously registered container-ready listener. */
    public static void unregisterStructureContainerReady(StructureContainerReady listener) {
        READY.remove(listener);
    }

    /**
     * Number of registered pre-step listeners. Primarily diagnostic.
     */
    public static int prePhysicsTickListenerCount() {
        return PRE_TICK.size();
    }

    /**
     * Number of registered post-step listeners. Primarily diagnostic.
     */
    public static int postPhysicsTickListenerCount() {
        return POST_TICK.size();
    }

    /**
     * Number of registered container-ready listeners. Primarily diagnostic.
     */
    public static int structureContainerReadyListenerCount() {
        return READY.size();
    }

    /**
     * Internal dispatch point used by the physics tick loop.
     */
    public static void firePrePhysicsTick(PhysicsTickEvent event) {
        for (PrePhysicsTick listener : PRE_TICK) {
            try {
                listener.onPrePhysicsTick(event);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Pre-physics-tick listener failed", t);
            }
        }
    }

    /**
     * Internal dispatch point used by the physics tick loop.
     */
    public static void firePostPhysicsTick(PhysicsTickEvent event) {
        for (PostPhysicsTick listener : POST_TICK) {
            try {
                listener.onPostPhysicsTick(event);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Post-physics-tick listener failed", t);
            }
        }
    }

    /**
     * Internal dispatch point used when a level's physics world is created.
     */
    public static void fireStructureContainerReady(StructureContainerReadyEvent event) {
        for (StructureContainerReady listener : READY) {
            try {
                listener.onStructureContainerReady(event);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure-container-ready listener failed", t);
            }
        }
    }

    /**
     * Removes all listeners. Intended for tests and process shutdown.
     */
    public static void clear() {
        PRE_TICK.clear();
        POST_TICK.clear();
        READY.clear();
    }
}
