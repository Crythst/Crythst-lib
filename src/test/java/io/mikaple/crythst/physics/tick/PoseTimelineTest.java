package io.mikaple.crythst.physics.tick;

import io.mikaple.crythst.math.InterpolatedPose;
import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseTimelineTest {
    private static final long MS = 1_000_000L;
    private static final long TICK = 50L * MS;

    @Test
    void samplesBetweenPushesAtFrameRate() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);

        assertEquals(0.0, timeline.sample(TICK).position().x, 1.0e-9,
                "at the one-tick delay the previous pose is held");
        assertEquals(0.5, timeline.sample(TICK + TICK / 2).position().x, 1.0e-9,
                "half a tick later the sample is halfway between the poses");
        assertEquals(1.0, timeline.sample(2 * TICK).position().x, 1.0e-9,
                "the newest pose is reached one tick after it was published");
    }

    @Test
    void exactTickMultiplesStayOnTheOneTickDelayedPose() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);
        timeline.push(poseX(2.0), 2 * TICK);

        assertEquals(1.0, timeline.sample(2 * TICK).position().x, 1.0e-9);
        assertEquals(1.5, timeline.sample(2 * TICK + TICK / 2).position().x, 1.0e-9);

        timeline.push(poseX(3.0), 3 * TICK);
        assertEquals(2.0, timeline.sample(3 * TICK).position().x, 1.0e-9);
    }

    @Test
    void neverExtrapolatesPastTheNewestPose() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);

        final PoseTimeline.Sample sample = timeline.sampleAt(100 * TICK);
        assertEquals(1.0, sample.pose().position().x, 1.0e-9);
        assertTrue(sample.clamped(), "holding the newest pose must report clamped");
    }

    @Test
    void teleportSnapsInsteadOfInterpolating() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(100.0), TICK);

        assertEquals(1L, timeline.snapCount());
        assertEquals(100.0, timeline.sample(TICK).position().x, 1.0e-9);
        assertEquals(100.0, timeline.sample(TICK + TICK / 2).position().x, 1.0e-9);
        assertEquals(100.0, timeline.sample(TICK / 2).position().x, 1.0e-9,
                "the old pose must not survive a teleport snap");
    }

    @Test
    void staleGapWithMovementSnaps() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), 2_000L * MS);

        assertEquals(1L, timeline.snapCount());
        assertEquals(1.0, timeline.sample(2_000L * MS).position().x, 1.0e-9);
    }

    @Test
    void explicitResetSnapsToTheNewPose() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);
        timeline.reset(poseX(25.0), TICK + TICK / 2);

        assertEquals(25.0, timeline.sample(TICK + TICK / 2).position().x, 1.0e-9);
        assertEquals(25.0, timeline.sample(TICK + 2 * TICK).position().x, 1.0e-9);
    }

    @Test
    void lateTickHoldsTheCurrentPoseAndResumesWithoutExtrapolation() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);
        timeline.push(poseX(2.0), 2 * TICK);

        // The next publish is 30 ms late. The sampler must not run past the
        // current pose; it holds, then resumes when the late pose arrives.
        assertEquals(2.0, timeline.sample(2 * TICK + 60 * MS).position().x, 1.0e-9);
        timeline.push(poseX(3.0), 2 * TICK + 80 * MS);
        final double resumed = timeline.sample(2 * TICK + 90 * MS).position().x;
        assertTrue(resumed >= 2.0 && resumed <= 3.0,
                "sample after a late publish should resume between poses, got " + resumed);
    }

    @Test
    void frameRateSmoothnessBeatsClientAlphaAgainstAServerTickClock() {
        final int framesPerTick = 50;
        final int ticks = 8;
        final long clientPhase = 25L * MS;

        final InterpolatedPose legacy = new InterpolatedPose();
        final PoseTimeline timed = new PoseTimeline();
        legacy.reset(poseX(0.0));
        timed.reset(poseX(0.0), 0L);

        double legacyMaxStep = 0.0;
        double timedMaxStep = 0.0;
        double legacyPrevious = 0.0;
        double timedPrevious = 0.0;

        for (int tick = 0; tick <= ticks; tick++) {
            if (tick > 0) {
                legacy.push(poseX(tick));
                timed.push(poseX(tick), tick * TICK);
            }
            for (int frame = 1; frame < framesPerTick; frame++) {
                final long now = tick * TICK + frame * (TICK / framesPerTick);

                // Old path: client tick boundary offset by 25 ms from the
                // server tick, sampled with the client partial tick.
                final long clientElapsed = Math.floorMod(now - clientPhase, TICK);
                final double alpha = clientElapsed / (double) TICK;
                final double legacySample = legacy.sample(alpha).position().x;
                legacyMaxStep = Math.max(legacyMaxStep, Math.abs(legacySample - legacyPrevious));
                legacyPrevious = legacySample;

                final double timedSample = timed.sample(now).position().x;
                timedMaxStep = Math.max(timedMaxStep, Math.abs(timedSample - timedPrevious));
                timedPrevious = timedSample;
            }
        }

        // Motion is one block per 50 ms tick. A 1 ms frame should move about
        // 0.02 blocks. Alpha sampling jumps by roughly one whole tick when a
        // server update lands mid-client-tick.
        assertTrue(legacyMaxStep > 0.25,
                "legacy alpha sampling should show a visible jump, saw " + legacyMaxStep);
        assertTrue(timedMaxStep < 0.05,
                "timeline sampling should stay frame-rate smooth, saw " + timedMaxStep);
    }

    @Test
    void rotationUsesSlerpAndSnapsOnLargeUpdate() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseYaw(0.0), 0L);
        timeline.push(poseYaw(Math.PI / 2.0), TICK);
        final double halfway = yawOf(timeline.sample(TICK + TICK / 2));
        assertEquals(Math.PI / 4.0, halfway, 1.0e-6);

        timeline.push(poseYaw(Math.PI), 2 * TICK);
        assertEquals(Math.PI / 2.0, yawOf(timeline.sample(2 * TICK)), 1.0e-6);
        // A >120 degree jump is a snap.
        timeline.push(poseYaw(Math.PI + Math.PI * 0.9), 3 * TICK);
        assertEquals(1L, timeline.snapCount());
    }

    @Test
    void duplicatePoseUpdatesDoNotCreateJitter() {
        final PoseTimeline timeline = new PoseTimeline();
        timeline.reset(poseX(0.0), 0L);
        timeline.push(poseX(1.0), TICK);
        timeline.push(poseX(1.0), TICK);
        timeline.push(poseX(1.0), 2 * TICK);

        assertEquals(0L, timeline.snapCount());
        assertEquals(1.0, timeline.sample(2 * TICK).position().x, 1.0e-9);
        assertEquals(3L, timeline.pushCount());
    }

    private static Transform3d poseX(double x) {
        return Transform3d.of(new Vector3d(x, 0.0, 0.0));
    }

    private static Transform3d poseYaw(double yaw) {
        return Transform3d.of(new Vector3d(), new Quaterniond().rotateY(yaw));
    }

    private static double yawOf(Transform3d pose) {
        final Quaterniond q = pose.rotation();
        return 2.0 * Math.atan2(q.y(), q.w());
    }
}
