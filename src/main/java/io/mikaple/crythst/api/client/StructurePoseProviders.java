package io.mikaple.crythst.api.client;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registration and fallback logic for {@link StructurePoseProvider}.
 *
 * <p>Kept package-private so the public surface is the interface itself. The
 * list is copy-on-write because registration is rare while a frame may resolve
 * hundreds of poses.</p>
 */
final class StructurePoseProviders {
    private static final List<StructurePoseProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    static final StructurePoseProvider DEFAULT =
            (bodyId, partialTick, frameTimeNanos) ->
                    defaultPose(bodyId, frameTimeNanos);

    private StructurePoseProviders() {
    }

    static AutoCloseable register(StructurePoseProvider provider) {
        if (provider == null) {
            throw new NullPointerException("provider");
        }
        PROVIDERS.add(provider);
        return () -> unregister(provider);
    }

    static void unregister(StructurePoseProvider provider) {
        PROVIDERS.remove(provider);
    }

    static @Nullable StructureRenderPose resolve(long bodyId, double partialTick,
                                                 long frameTimeNanos) {
        // Newest registration wins, so a compatibility mod can override an
        // earlier compatibility mod without knowing it.
        for (int i = PROVIDERS.size() - 1; i >= 0; i--) {
            final StructurePoseProvider provider = PROVIDERS.get(i);
            try {
                final StructureRenderPose pose = provider.pose(bodyId, partialTick, frameTimeNanos);
                if (pose != null) {
                    return pose;
                }
            } catch (RuntimeException failure) {
                Crythst.LOGGER.warn("Structure pose provider {} failed for body {}",
                        provider.getClass().getName(), bodyId, failure);
            }
        }
        return DEFAULT.pose(bodyId, partialTick, frameTimeNanos);
    }

    /**
     * Built-in sampler. Integrated-client structures use their timestamped
     * timeline directly; network structures use the sync cache's timeline.
     */
    private static @Nullable StructureRenderPose defaultPose(long bodyId, long frameTimeNanos) {
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            // renderStateAt is documented as render-thread safe and does not
            // touch native body state. It is only used here to identify the
            // body; the actual pose comes from the timeline below.
            if (structure.renderStateAt(frameTimeNanos).bodyId() != bodyId) {
                continue;
            }
            final double interval = Math.max(
                    StructureRenderPose.DEFAULT_INTERVAL_SECONDS,
                    structure.renderTimelineDelayNanos() / 1.0e9);
            return StructurePoseSampling.sample(bodyId, structure::interpolatedPoseAt,
                    frameTimeNanos, interval);
        }

        if (ClientStructureCache.get(bodyId) == null) {
            return null;
        }
        return StructurePoseSampling.sample(bodyId,
                time -> ClientStructureCache.interpolatedPose(bodyId, time),
                frameTimeNanos,
                ClientStructureCache.interpolatedIntervalSeconds(bodyId));
    }
}
