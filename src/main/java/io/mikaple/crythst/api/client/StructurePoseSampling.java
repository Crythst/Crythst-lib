package io.mikaple.crythst.api.client;

import io.mikaple.crythst.math.Transform3d;

import java.util.function.LongFunction;

/**
 * Samples a timestamped pose function for {@link StructureRenderData}.
 *
 * <p>The function is expected to behave like
 * {@code PhysicsStructure#interpolatedPoseAt(long)} or
 * {@code ClientStructureCache#interpolatedPose(long, long)}: it returns the
 * previous endpoint for far-past times, the current endpoint for far-future
 * times, and an interpolated sample otherwise. Sampling the two extreme times
 * lets us expose the endpoint pair through the public timeline API without
 * reaching into its private state.</p>
 */
final class StructurePoseSampling {
    /** About 31 years; large enough to sit outside any live timeline. */
    private static final long FAR_NANOS = 1_000_000_000_000_000_000L;

    private StructurePoseSampling() {
    }

    static StructureRenderPose sample(long bodyId, LongFunction<Transform3d> sampler,
                                      long frameTimeNanos, double intervalSeconds) {
        final Transform3d pose = sampler.apply(frameTimeNanos);
        final Transform3d previous = sampler.apply(past(frameTimeNanos));
        final Transform3d current = sampler.apply(future(frameTimeNanos));
        return new StructureRenderPose(bodyId, pose, previous, current, intervalSeconds);
    }

    static long past(long frameTimeNanos) {
        final long past = frameTimeNanos - FAR_NANOS;
        // Saturation avoids turning a far-past sample into a far-future one
        // through signed overflow.
        return past > frameTimeNanos ? Long.MIN_VALUE / 2L : past;
    }

    static long future(long frameTimeNanos) {
        final long future = frameTimeNanos + FAR_NANOS;
        return future < frameTimeNanos ? Long.MAX_VALUE / 2L : future;
    }
}
