package io.mikaple.crythst.api.client;

import org.jetbrains.annotations.Nullable;

/**
 * Supplies the render pose used by {@link StructureRenderView}.
 *
 * <p>The default implementation samples the timestamped render timeline
 * (singleplayer) or the network pose timeline (multiplayer). It deliberately
 * ignores {@code partialTick}: Crythst publishes poses with wall-clock
 * timestamps so the sample stays smooth even when a server tick lands inside a
 * client tick. A provider registered by another mod may use the partial tick,
 * for example to match a Create renderer's own interpolation clock.</p>
 *
 * <p>Providers are queried in reverse registration order; the most recently
 * registered provider wins. Returning {@code null} falls through to the next
 * provider and finally to the built-in timeline sampler, so a provider can
 * override only the body ids it knows and leave the rest unobstructed.</p>
 *
 * <p>This type is client-only and must not be loaded on a dedicated server.</p>
 */
@FunctionalInterface
public interface StructurePoseProvider {
    /**
     * Returns the render pose for one structure.
     *
     * @param bodyId         physics body id or network snapshot id
     * @param partialTick    client tick partial tick in {@code [0, 1)}
     * @param frameTimeNanos monotonic frame time shared by one client frame
     * @return the pose, or {@code null} to let the next provider handle it
     */
    @Nullable StructureRenderPose pose(long bodyId, double partialTick, long frameTimeNanos);

    /**
     * Registers a provider. The handle removes it again when closed.
     */
    static AutoCloseable register(StructurePoseProvider provider) {
        return StructurePoseProviders.register(provider);
    }

    /** Removes a provider that was previously registered. */
    static void unregister(StructurePoseProvider provider) {
        StructurePoseProviders.unregister(provider);
    }

    /**
     * Resolves a pose through registered overrides and then the built-in
     * timestamped timeline provider.
     */
    static @Nullable StructureRenderPose poseFor(long bodyId, double partialTick,
                                                 long frameTimeNanos) {
        return StructurePoseProviders.resolve(bodyId, partialTick, frameTimeNanos);
    }

    /** The built-in timestamped-timeline provider. */
    static StructurePoseProvider defaultProvider() {
        return StructurePoseProviders.DEFAULT;
    }
}
