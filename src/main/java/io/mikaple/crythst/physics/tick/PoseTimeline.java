package io.mikaple.crythst.physics.tick;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniondc;

import java.util.Objects;

/**
 * Timestamped two-pose render timeline for a moving structure.
 *
 * <p>Physics poses are published on the <em>server</em> tick boundary, while
 * Minecraft interpolates entities and the camera with the <em>client</em> tick
 * partial tick. In singleplayer those two clocks are driven by different
 * threads and are not phase-locked, so sampling a server-tick pose pair with
 * the client alpha introduces a discontinuity whenever a server tick lands
 * inside a client tick. In multiplayer the same pose is delivered at the
 * network update rate and sampled at {@code 20&nbsp;Hz} with no interpolation
 * at all.</p>
 *
 * <p>This timeline removes the dependency on both tick clocks. Each pose is
 * stored with the monotonic timestamp at which it was published. Rendering
 * samples at {@code now - one tick}: one pose interval behind the newest
 * pose, exactly like vanilla's previous/current entity interpolation. The
 * sample never extrapolates; if a tick is late the timeline holds the newest
 * pose until the next update arrives.</p>
 *
 * <p>The delay is a smoothed (EMA) estimate of the publish interval, clamped
 * to a sane range. A burst of catch-up ticks therefore advances the visible
 * motion over roughly one normal tick instead of teleporting, and a slower
 * {@code /tick rate} automatically gets a correspondingly longer delay.</p>
 *
 * <p>Threat model: {@link #push} is normally called by the server thread (local
 * physics) or the client thread (network receiver), while {@link #sample} is
 * called by the render thread. The state is an immutable record stored in a
 * volatile field, so reads are tear-free and lock-free.</p>
 */
public final class PoseTimeline {
    /** One Minecraft game tick at the default 20 TPS. */
    public static final long DEFAULT_DELAY_NANOS = 50_000_000L;
    /** Lower bound for the smoothed publish interval. */
    private static final long MIN_DELAY_NANOS = 10_000_000L;
    /** Upper bound so a stalled publisher cannot add unbounded latency. */
    private static final long MAX_DELAY_NANOS = 250_000_000L;
    /** A gap this large re-anchors the timeline instead of interpolating across it. */
    private static final long STALE_GAP_NANOS = 1_000_000_000L;
    /** A stale gap needs at least this much movement to count as a teleport. */
    private static final double STALE_DISTANCE = 0.25;
    /** A single update moving farther than this is treated as a teleport. */
    private static final double TELEPORT_DISTANCE = 16.0;
    /** A single update rotating farther than this is treated as a teleport. */
    private static final double TELEPORT_ANGLE = Math.toRadians(120.0);
    private static final double SAME_POSE_DISTANCE = 1.0e-7;
    private static final double SAME_POSE_ANGLE = 1.0e-6;

    private record State(Transform3d previous, Transform3d current,
                         long previousNanos, long currentNanos,
                         long intervalNanos, long pushes, long snaps,
                         double lastDeltaMetres) {
    }

    private static final State EMPTY = new State(
            Transform3d.IDENTITY, Transform3d.IDENTITY, 0L, 0L,
            DEFAULT_DELAY_NANOS, 0L, 0L, 0.0);

    private volatile State state = EMPTY;

    /**
     * One sampled frame.
     *
     * @param pose    interpolated structure pose
     * @param alpha   interpolation fraction in {@code [0, 1]}
     * @param clamped {@code true} when the sample hit the previous/current end
     *                (the timeline held instead of extrapolating)
     */
    public record Sample(Transform3d pose, double alpha, boolean clamped) {
    }

    /**
     * Drops the history and snaps to {@code pose}. Use after teleports,
     * structure splits, reindexing, or a full network resync.
     */
    public void reset(Transform3d pose, long nanos) {
        Objects.requireNonNull(pose, "pose");
        final State old = state;
        // Keep the snap counter meaningful for diagnostics: an explicit reset
        // after the timeline was already primed is a discontinuity.
        final long snaps = old.pushes == 0L ? old.snaps : old.snaps + 1L;
        state = new State(pose, pose, nanos, nanos,
                DEFAULT_DELAY_NANOS, 1L, snaps, 0.0);
    }

    /**
     * Records a newly published pose. Out-of-order timestamps and obvious
     * teleports snap instead of producing a bogus interpolation pair.
     */
    public void push(Transform3d pose, long nanos) {
        Objects.requireNonNull(pose, "pose");
        final State old = state;

        if (old.pushes == 0L) {
            state = new State(pose, pose, nanos, nanos,
                    old.intervalNanos, 1L, old.snaps, 0.0);
            return;
        }

        final double delta = old.current.position().distance(pose.position());
        final double angle = angleBetween(old.current, pose);

        if (nanos <= old.currentNanos) {
            if (isSamePose(delta, angle)) {
                return;
            }
            // A pose changed without time advancing (bad timestamp or a hard
            // set). Snap rather than divide by a non-positive interval.
            final long anchor = Math.max(nanos, old.currentNanos);
            state = new State(pose, pose, anchor, anchor,
                    old.intervalNanos, old.pushes + 1L, old.snaps + 1L, delta);
            return;
        }

        final long gap = nanos - old.currentNanos;
        final long boundedGap = Math.clamp(gap, MIN_DELAY_NANOS, MAX_DELAY_NANOS);
        // EMA keeps the one-tick delay stable through isolated early/late
        // ticks, and still adapts to a changed /tick rate within a few ticks.
        final long interval = Math.clamp(
                (old.intervalNanos * 3L + boundedGap) / 4L,
                MIN_DELAY_NANOS, MAX_DELAY_NANOS);

        final boolean teleport = delta > TELEPORT_DISTANCE || angle > TELEPORT_ANGLE;
        final boolean stale = gap > STALE_GAP_NANOS && delta > STALE_DISTANCE;
        if (teleport || stale) {
            state = new State(pose, pose, nanos, nanos,
                    interval, old.pushes + 1L, old.snaps + 1L, delta);
            return;
        }

        state = new State(old.current, pose, old.currentNanos, nanos,
                interval, old.pushes + 1L, old.snaps, delta);
    }

    /** Convenience overload for callers that only need the pose. */
    public Transform3d sample(long nowNanos) {
        return sampleAt(nowNanos).pose();
    }

    /**
     * Samples the timeline for a render frame at {@code nowNanos}.
     *
     * <p>The newest pose is delayed by one smoothed publish interval. Before
     * the previous pose the previous pose is held; at or after the current
     * pose the current pose is held. There is no extrapolation.</p>
     */
    public Sample sampleAt(long nowNanos) {
        final State s = state;
        if (s.pushes < 2L || s.currentNanos <= s.previousNanos) {
            return new Sample(s.current, 1.0, true);
        }

        final long renderNanos = nowNanos - s.intervalNanos;
        if (renderNanos <= s.previousNanos) {
            return new Sample(s.previous, 0.0, true);
        }
        if (renderNanos >= s.currentNanos) {
            return new Sample(s.current, 1.0, true);
        }

        final double alpha = (renderNanos - s.previousNanos)
                / (double) (s.currentNanos - s.previousNanos);
        return new Sample(s.previous.interpolate(s.current, alpha), alpha, false);
    }

    /** Smoothed one-tick delay currently used for sampling, in nanoseconds. */
    public long delayNanos() {
        return state.intervalNanos;
    }

    /** Number of poses recorded since construction. Diagnostics only. */
    public long pushCount() {
        return state.pushes;
    }

    /** Number of teleports/out-of-order updates/stale gaps snapped over. */
    public long snapCount() {
        return state.snaps;
    }

    /** Translation between the two poses currently buffered, in metres. */
    public double lastTranslationDelta() {
        return state.lastDeltaMetres;
    }

    private static boolean isSamePose(double delta, double angle) {
        return delta <= SAME_POSE_DISTANCE && angle <= SAME_POSE_ANGLE;
    }

    private static double angleBetween(Transform3d first, Transform3d second) {
        final Quaterniondc a = first.rotation();
        final Quaterniondc b = second.rotation();
        final double dot = Math.abs(a.x() * b.x() + a.y() * b.y()
                + a.z() * b.z() + a.w() * b.w());
        return 2.0 * Math.acos(Math.min(1.0, dot));
    }
}
