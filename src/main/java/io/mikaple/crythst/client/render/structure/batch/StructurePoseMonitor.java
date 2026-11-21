package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniondc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rate-limited frame-to-frame pose-delta diagnostics for structure rendering.
 *
 * <p>A smooth moving structure changes position by a roughly constant amount per
 * rendered frame. A timeline glitch (server/client phase mismatch, a stalled
 * pose publisher, or a teleport that was not snapped) shows up as one or more
 * frames whose delta is much larger than its neighbours. The monitor keeps the
 * maximum per-frame translation/rotation over a two-second window and logs it
 * at most once per window; it never logs at INFO and never allocates per frame
 * beyond the small body map.</p>
 *
 * <p>Render thread only. Exposed through
 * {@link BatchedStructureRenderer#poseDiagnosticsText()} so it can be appended
 * to {@code /crythst timing} without changing the shared command file.</p>
 */
final class StructurePoseMonitor {
    private static final long REPORT_INTERVAL_NANOS = 2_000_000_000L;
    private static final double LOG_THRESHOLD_METRES = 0.05;
    private static final int MAX_TRACKED_BODIES = 512;

    private final Map<Long, Transform3d> previous = new HashMap<>();

    private double frameMaxDeltaMetres;
    private double frameMaxAngleRadians;
    private double windowMaxDeltaMetres;
    private double windowMaxAngleRadians;

    // Volatile so the server-thread command snippet can read the summary while
    // the render thread is updating the window.
    private volatile double reportedDeltaMetres;
    private volatile double reportedAngleRadians;
    private volatile long frames;
    private volatile int trackedBodies;

    private long lastReportNanos;
    private long windowStartNanos;

    void beginFrame(long nowNanos) {
        frameMaxDeltaMetres = 0.0;
        frameMaxAngleRadians = 0.0;
        if (windowStartNanos == 0L) {
            windowStartNanos = nowNanos;
            lastReportNanos = nowNanos;
        }
    }

    void observe(long bodyId, Transform3d pose) {
        final Transform3d last = previous.put(bodyId, pose);
        if (last == null) {
            return;
        }
        final double delta = last.position().distance(pose.position());
        if (delta > frameMaxDeltaMetres) {
            frameMaxDeltaMetres = delta;
        }
        final double angle = angleBetween(last, pose);
        if (angle > frameMaxAngleRadians) {
            frameMaxAngleRadians = angle;
        }
    }

    void endFrame(long nowNanos) {
        frames++;
        if (frameMaxDeltaMetres > windowMaxDeltaMetres) {
            windowMaxDeltaMetres = frameMaxDeltaMetres;
        }
        if (frameMaxAngleRadians > windowMaxAngleRadians) {
            windowMaxAngleRadians = frameMaxAngleRadians;
        }

        trackedBodies = previous.size();
        if (trackedBodies > MAX_TRACKED_BODIES) {
            previous.clear();
            trackedBodies = 0;
        }

        if (nowNanos - lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }

        reportedDeltaMetres = windowMaxDeltaMetres;
        reportedAngleRadians = windowMaxAngleRadians;
        if (reportedDeltaMetres >= LOG_THRESHOLD_METRES) {
            Crythst.LOGGER.debug(
                    "Crythst structure pose delta: maxFrameDelta={}m maxFrameAngle={}rad "
                            + "frames={} window={}ms tracked={}",
                    String.format(Locale.ROOT, "%.4f", reportedDeltaMetres),
                    String.format(Locale.ROOT, "%.4f", reportedAngleRadians),
                    frames,
                    (nowNanos - windowStartNanos) / 1_000_000L,
                    trackedBodies);
        }

        lastReportNanos = nowNanos;
        windowStartNanos = nowNanos;
        windowMaxDeltaMetres = 0.0;
        windowMaxAngleRadians = 0.0;
    }

    String summary() {
        return String.format(Locale.ROOT,
                "structurePoseDelta maxFrame=%sm/%srad frames=%d tracked=%d",
                String.format(Locale.ROOT, "%.4f", reportedDeltaMetres),
                String.format(Locale.ROOT, "%.4f", reportedAngleRadians),
                frames,
                trackedBodies);
    }

    private static double angleBetween(Transform3d first, Transform3d second) {
        final Quaterniondc a = first.rotation();
        final Quaterniondc b = second.rotation();
        final double dot = Math.abs(a.x() * b.x() + a.y() * b.y()
                + a.z() * b.z() + a.w() * b.w());
        return 2.0 * Math.acos(Math.min(1.0, dot));
    }
}
