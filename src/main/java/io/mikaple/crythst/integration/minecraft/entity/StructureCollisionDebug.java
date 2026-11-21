package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.Crythst;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate-limited diagnostics for structure collision corrections.
 *
 * <p>The last correction is always kept in memory so a debug command or a
 * crash report can explain why the player was moved or why a correction was
 * skipped. Logging is opt-in through the
 * {@code -Dcrythst.debug.collision=true} system property and is rate limited
 * to one line per second, so it is safe to leave compiled in.</p>
 *
 * <p>Both the server manager and the client predictor record here, which makes
 * it possible to see whether a frame was corrected locally, deferred to the
 * client, or forced by an authoritative teleport.</p>
 */
public final class StructureCollisionDebug {
    /** Minimum interval between log lines. */
    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;
    /** Two normals more different than this within one tick indicate a flip. */
    private static final double FLIP_DOT_THRESHOLD = 0.5;
    /** A flip counter is only meaningful for contacts this close in time. */
    private static final long FLIP_WINDOW_NANOS = 150_000_000L;

    private static final boolean LOGGING = Boolean.getBoolean("crythst.debug.collision");

    /** Where a correction came from. */
    public enum Path {
        CLIENT_PREDICTION,
        SERVER_ENTITY,
        SERVER_PLAYER_TELEPORT,
        SERVER_PLAYER_DEFERRED
    }

    /**
     * One correction attempt.
     *
     * @param nanos          {@link System#nanoTime()} when it was recorded
     * @param path           which authority handled it
     * @param bodyIds        body ids involved, comma separated ({@code -} when unknown)
     * @param penetration    overlap depth before the margin, in metres
     * @param normal         unit exit normal
     * @param offset         applied correction, including margin
     * @param velocityBefore entity velocity before the correction
     * @param velocityAfter  entity velocity after the correction
     * @param applied        whether the position/velocity change was applied
     * @param note           short reason for skipping or context
     */
    public record Sample(long nanos, Path path, String bodyIds, double penetration,
                         Vector3d normal, Vector3d offset,
                         Vec3 velocityBefore, Vec3 velocityAfter,
                         boolean applied, String note) {
        public Sample {
            normal = new Vector3d(normal);
            offset = new Vector3d(offset);
        }

        @Override
        public Vector3d normal() {
            return new Vector3d(normal);
        }

        @Override
        public Vector3d offset() {
            return new Vector3d(offset);
        }

        /** One-line form suitable for a log or a status overlay. */
        public String describe() {
            return path + (applied ? " applied" : " skipped")
                    + " body=" + bodyIds
                    + String.format(" pen=%.4f", penetration)
                    + " n=" + format(normal)
                    + " off=" + format(offset)
                    + " v=" + format(velocityBefore) + "->" + format(velocityAfter)
                    + (note == null || note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    private static volatile Sample last;
    private static volatile long lastLogNanos;
    private static final AtomicLong corrections = new AtomicLong();
    private static final AtomicLong deferred = new AtomicLong();
    private static final AtomicLong flips = new AtomicLong();

    private StructureCollisionDebug() {
    }

    /** Records a client-side prediction attempt. */
    public static void client(String bodyIds, double penetration, Vector3d normal,
                              Vector3d offset, Vec3 velocityBefore, Vec3 velocityAfter,
                              boolean applied, String note) {
        record(new Sample(System.nanoTime(), Path.CLIENT_PREDICTION, bodyIds,
                penetration, normal, offset, velocityBefore, velocityAfter, applied, note));
    }

    /**
     * Records a server-side correction of a non-player entity. This is skipped
     * unless logging is enabled: a busy server can correct hundreds of items
     * and mobs per tick, and the trace is meant for the local player.
     */
    public static void serverEntity(String bodyIds, double penetration, Vector3d normal,
                                    Vector3d offset, Vec3 velocityBefore, Vec3 velocityAfter,
                                    boolean applied, String note) {
        if (!LOGGING) {
            return;
        }
        record(new Sample(System.nanoTime(), Path.SERVER_ENTITY, bodyIds,
                penetration, normal, offset, velocityBefore, velocityAfter, applied, note));
    }

    /**
     * Records a server-side player correction.
     *
     * @param teleported {@code true} when an authoritative teleport was sent,
     *                   {@code false} when the client prediction owns it
     */
    public static void serverPlayer(String bodyIds, double penetration, Vector3d normal,
                                    Vector3d offset, Vec3 velocityBefore, Vec3 velocityAfter,
                                    boolean teleported, String note) {
        record(new Sample(System.nanoTime(),
                teleported ? Path.SERVER_PLAYER_TELEPORT : Path.SERVER_PLAYER_DEFERRED,
                bodyIds, penetration, normal, offset, velocityBefore, velocityAfter,
                teleported, note));
    }

    private static void record(Sample sample) {
        final Sample previous = last;
        last = sample;
        if (sample.applied()) {
            corrections.incrementAndGet();
        } else {
            deferred.incrementAndGet();
        }
        if (previous != null && sample.nanos - previous.nanos <= FLIP_WINDOW_NANOS
                && previous.normal().dot(sample.normal()) < FLIP_DOT_THRESHOLD) {
            flips.incrementAndGet();
        }
        if (LOGGING) {
            logIfDue(sample);
        }
    }

    private static void logIfDue(Sample sample) {
        final long now = System.nanoTime();
        final long previous = lastLogNanos;
        if (now - previous < LOG_INTERVAL_NANOS) {
            return;
        }
        lastLogNanos = now;
        Crythst.LOGGER.info("[collision] {} corrections={} deferred={} flips={}",
                sample.describe(), corrections.get(), deferred.get(), flips.get());
    }

    /** Most recent sample, or {@code null} before the first contact. */
    public static Sample last() {
        return last;
    }

    /** Most recent sample rendered as a status string, or {@code null}. */
    public static String lastDescription() {
        final Sample sample = last;
        return sample == null ? null : sample.describe();
    }

    /** Number of applied corrections since startup. */
    public static long correctionCount() {
        return corrections.get();
    }

    /** Number of skipped/deferred corrections since startup. */
    public static long deferredCount() {
        return deferred.get();
    }

    /** Number of near-instant normal reversals. Non-zero means oscillation. */
    public static long flipCount() {
        return flips.get();
    }

    /** Clears counters and the last sample. Diagnostics only. */
    public static void reset() {
        last = null;
        lastLogNanos = 0L;
        corrections.set(0L);
        deferred.set(0L);
        flips.set(0L);
    }

    private static String format(Vector3d value) {
        return String.format("(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }

    private static String format(Vec3 value) {
        return String.format("(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }
}
