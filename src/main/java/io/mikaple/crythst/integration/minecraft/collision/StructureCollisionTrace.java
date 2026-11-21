package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.Crythst;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate-limited diagnostics for the movement collision path.
 *
 * <p>The last sample is always kept in memory so a status overlay or a crash
 * report can explain why an entity stopped. Logging is opt-in through
 * {@code -Dcrythst.debug.collision=true} and limited to one line per second,
 * which is safe to leave compiled in.</p>
 */
public final class StructureCollisionTrace {
    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;
    private static final boolean LOGGING = Boolean.getBoolean("crythst.debug.collision");

    /**
     * The last movement collision.
     *
     * @param nanos          time the sample was recorded
     * @param side           {@code server} or {@code client}
     * @param entityType     entity type id
     * @param bodyIds        structure body ids involved
     * @param penetration    deepest overlap seen before correction
     * @param normal         last contact normal
     * @param motionBefore   requested motion
     * @param motionAfter    motion returned after structure and vanilla collision
     * @param substeps       substeps used
     * @param contacts       number of distinct contact normals
     * @param grounded       whether the solve found a structure support
     * @param stepped        whether a structure step-up was applied
     * @param note           short context string
     */
    public record Sample(long nanos, String side, String entityType, String bodyIds,
                         double penetration, Vector3d normal,
                         Vec3 motionBefore, Vec3 motionAfter,
                         int substeps, int contacts, boolean grounded,
                         boolean stepped, String note) {
        public Sample {
            normal = new Vector3d(normal);
        }

        @Override
        public Vector3d normal() {
            return new Vector3d(normal);
        }

        /** One-line form suitable for a log or a status overlay. */
        public String describe() {
            return side + " movement body=" + bodyIds
                    + String.format(" pen=%.4f", penetration)
                    + " n=" + format(normal)
                    + " motion=" + format(motionBefore) + "->" + format(motionAfter)
                    + " substeps=" + substeps + " contacts=" + contacts
                    + (grounded ? " ground" : "") + (stepped ? " step" : "")
                    + (note == null || note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    private static volatile Sample last;
    private static volatile long lastLogNanos;
    private static final AtomicLong collisions = new AtomicLong();
    private static final AtomicLong suppressed = new AtomicLong();

    private StructureCollisionTrace() {
    }

    /** Records one movement collision sample. */
    public static void record(String side, String entityType, StructureMotionSolver.Result result,
                              Vec3 motionBefore, Vec3 motionAfter, String note) {
        final Vector3d normal = result.contacts().isEmpty()
                ? new Vector3d()
                : result.contacts().get(result.contacts().size() - 1).normal();
        record(new Sample(System.nanoTime(), side, entityType, result.bodyIds(),
                result.penetration(), normal, motionBefore, motionAfter,
                result.substeps(), result.contacts().size(), result.support(),
                result.stepped(), note));
    }

    /** Records a sample that did not resolve a contact. */
    public static void record(Vec3 motionBefore, Vec3 motionAfter, String note) {
        record(new Sample(System.nanoTime(), "-", "-", "-", 0.0, new Vector3d(),
                motionBefore, motionAfter, 0, 0, false, false, note));
    }

    private static void record(Sample sample) {
        last = sample;
        if (sample.contacts() > 0) {
            collisions.incrementAndGet();
        } else {
            suppressed.incrementAndGet();
        }
        if (LOGGING) {
            logIfDue(sample);
        }
    }

    private static void logIfDue(Sample sample) {
        final long now = System.nanoTime();
        if (now - lastLogNanos < LOG_INTERVAL_NANOS) {
            return;
        }
        lastLogNanos = now;
        Crythst.LOGGER.info("[collision] {} contacts={} suppressed={}",
                sample.describe(), collisions.get(), suppressed.get());
    }

    /** Most recent sample, or {@code null} before the first movement. */
    public static Sample last() {
        return last;
    }

    /** Most recent sample as a status string, or {@code null}. */
    public static String lastDescription() {
        final Sample sample = last;
        return sample == null ? null : sample.describe();
    }

    /** Number of movement solves that produced at least one contact. */
    public static long collisionCount() {
        return collisions.get();
    }

    /** Clears the stored sample. Diagnostics only. */
    public static void reset() {
        last = null;
        lastLogNanos = 0L;
        collisions.set(0L);
        suppressed.set(0L);
    }

    private static String format(Vector3d value) {
        return String.format("(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }

    private static String format(Vec3 value) {
        return String.format("(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }
}
