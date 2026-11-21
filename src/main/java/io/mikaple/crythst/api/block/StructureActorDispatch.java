package io.mikaple.crythst.api.block;

import java.util.List;
import java.util.function.Consumer;

/**
 * Small fault-isolating invoker for {@link BlockEntityStructureActor} lists.
 *
 * <p>Crythst's server tick loop uses this to run every actor for one structure
 * even if an earlier actor throws. The failure handler receives the throwable
 * and decides how to log or rate-limit it; the next actor always runs.</p>
 *
 * <p>This type performs no Minecraft or physics access, which makes it a
 * convenient seam for tests that need to exercise actor dispatch without
 * bootstrapping a level or a native physics world.</p>
 */
public final class StructureActorDispatch {
    private StructureActorDispatch() {
    }

    /**
     * Invokes every actor in order.
     *
     * @param actors         immutable actor snapshot; must not be {@code null}
     * @param context        context passed to each actor; must not be {@code null}
     * @param failureHandler receives each failure, or {@code null} to ignore
     * @throws NullPointerException when {@code actors} or {@code context} is
     *                              {@code null}
     */
    public static void dispatch(List<BlockEntityStructureActor> actors,
                                StructurePhysicsContext context,
                                Consumer<Throwable> failureHandler) {
        if (actors == null) {
            throw new NullPointerException("actors");
        }
        if (context == null) {
            throw new NullPointerException("context");
        }
        for (BlockEntityStructureActor actor : actors) {
            try {
                actor.structurePhysicsTick(context);
            } catch (Throwable failure) {
                if (failureHandler != null) {
                    try {
                        failureHandler.accept(failure);
                    } catch (Throwable ignored) {
                        // A diagnostic callback must not break dispatch either.
                    }
                }
            }
        }
    }
}
