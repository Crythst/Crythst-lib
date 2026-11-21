package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Immutable process-wide tuning knobs for the portal view pipeline.
 *
 * <p>All values can be overridden at launch with system properties, which keeps
 * runtime A/B testing possible without rebuilding:</p>
 *
 * <pre>
 * -Dcrythst.portal.capture=false
 * -Dcrythst.portal.forceProcedural=true
 * -Dcrythst.portal.capture.dump=true
 * -Dcrythst.portal.capture.intervalMs=100
 * -Dcrythst.portal.capture.scale=1.0
 * </pre>
 */
final class PortalCaptureSettings {
    static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("crythst.portal.capture", "true"));
    static final boolean FORCE_PROCEDURAL =
            Boolean.getBoolean("crythst.portal.forceProcedural") || !ENABLED;
    static final boolean DUMP_TARGET = Boolean.getBoolean("crythst.portal.capture.dump");

    static final float SCALE = readScale();
    static final int MAX_WIDTH = 1280;
    static final int MAX_HEIGHT = 720;
    static final int MAX_PORTALS_PER_ROUND = 1;
    static final int SECTION_COMPILE_BUDGET = 8;
    static final double MAX_CAPTURE_DISTANCE = 160.0;
    static final double MAX_CAPTURE_DISTANCE_SQUARED =
            MAX_CAPTURE_DISTANCE * MAX_CAPTURE_DISTANCE;

    static final long BASE_INTERVAL_NANOS = readIntervalNanos();
    static final long MAX_INTERVAL_NANOS = Math.max(BASE_INTERVAL_NANOS, 250_000_000L);
    static final long TIMING_LOG_INTERVAL_NANOS = 1_000_000_000L;

    /** Camera motion below this is considered unchanged for texture reuse. */
    static final double REUSE_DISTANCE_SQUARED = 1.0e-4; // 0.01 block
    static final float REUSE_ANGLE_DEGREES = 0.05f;
    static final long REUSE_MAX_AGE_NANOS = 1_000_000_000L;

    private PortalCaptureSettings() {
    }

    static int[] targetSize(RenderTarget mainTarget) {
        int width = Math.max(1, Math.round(mainTarget.width * SCALE));
        int height = Math.max(1, Math.round(mainTarget.height * SCALE));
        final double cap = Math.min(MAX_WIDTH / (double) width, MAX_HEIGHT / (double) height);
        if (cap < 1.0) {
            width = Math.max(1, (int) Math.round(width * cap));
            height = Math.max(1, (int) Math.round(height * cap));
        }
        return new int[]{width, height};
    }

    private static long readIntervalNanos() {
        final String raw = System.getProperty("crythst.portal.capture.intervalMs");
        long millis = 100L;
        if (raw != null) {
            try {
                millis = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        return Math.max(50L, Math.min(2000L, millis)) * 1_000_000L;
    }

    private static float readScale() {
        final String raw = System.getProperty("crythst.portal.capture.scale");
        double scale = 1.0;
        if (raw != null) {
            try {
                scale = Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        return (float) Math.max(0.125, Math.min(1.0, scale));
    }
}
