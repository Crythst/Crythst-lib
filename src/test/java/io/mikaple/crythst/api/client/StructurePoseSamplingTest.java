package io.mikaple.crythst.api.client;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.tick.PoseTimeline;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StructurePoseSamplingTest {
    private static final long TICK = 50_000_000L;
    private static final double EPSILON = 1.0e-9;

    @Test
    void samplesTheInterpolatedPoseAndBothTimelineEndpoints() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);

        final StructureRenderPose pose = StructurePoseSampling.sample(
                4L, timeline::sample, TICK + TICK / 2, 0.05);

        assertEquals(0.5, pose.pose().position().x, EPSILON);
        assertEquals(0.0, pose.previousPose().position().x, EPSILON);
        assertEquals(1.0, pose.currentPose().position().x, EPSILON);
        assertEquals(0.05, pose.intervalSeconds(), EPSILON);
    }

    @Test
    void linearVelocityUsesTheTimestampedEndpointPair() {
        final StructureRenderPose pose = new StructureRenderPose(
                1L, poseX(0.5), poseX(0.0), poseX(1.0), 0.5);

        final Vector3d velocity = pose.velocityAt(new Vector3d(0.5, 0.0, 0.0));

        assertEquals(2.0, velocity.x, EPSILON);
        assertEquals(0.0, velocity.y, EPSILON);
        assertEquals(0.0, velocity.z, EPSILON);
    }

    @Test
    void registeredProvidersOverrideAndFallThroughInReverseOrder() throws Exception {
        final StructurePoseProvider first = (bodyId, partialTick, frameTime) ->
                StructureRenderPose.sampled(bodyId, poseX(1.0));
        final StructurePoseProvider second = (bodyId, partialTick, frameTime) ->
                StructureRenderPose.sampled(bodyId, poseX(2.0));

        try (AutoCloseable ignoredFirst = StructurePoseProvider.register(first);
             AutoCloseable ignoredSecond = StructurePoseProvider.register(second)) {
            final StructureRenderPose resolved = StructurePoseProvider.poseFor(7L, 0.0, 0L);
            assertNotNull(resolved);
            assertEquals(2.0, resolved.pose().position().x, EPSILON);
        }

        final StructurePoseProvider nullProvider = (bodyId, partialTick, frameTime) -> null;
        try (AutoCloseable ignoredNull = StructurePoseProvider.register(nullProvider);
             AutoCloseable ignoredFirst = StructurePoseProvider.register(first)) {
            final StructureRenderPose resolved = StructurePoseProvider.poseFor(7L, 0.0, 0L);
            assertNotNull(resolved);
            assertEquals(1.0, resolved.pose().position().x, EPSILON);
        }
    }

    @Test
    void registryHandleIsIdempotent() throws Exception {
        final AtomicLong calls = new AtomicLong();
        final StructurePoseProvider provider = (bodyId, partialTick, frameTime) -> {
            calls.incrementAndGet();
            return StructureRenderPose.sampled(bodyId, poseX(3.0));
        };
        final AutoCloseable handle = StructurePoseProvider.register(provider);
        try {
            assertNotNull(StructurePoseProvider.poseFor(1L, 0.0, 0L));
            assertNotNull(StructurePoseProvider.poseFor(1L, 0.0, 0L));
        } finally {
            handle.close();
            handle.close();
        }
        assertEquals(2L, calls.get());
    }

    private static Transform3d poseX(double x) {
        return Transform3d.of(new Vector3d(x, 0.0, 0.0));
    }
}
