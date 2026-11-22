package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Process-wide tuning knobs for the portal view pipeline.
 *
 * <p>Defaults implement the correct path: render the destination scene from the
 * transformed virtual camera every time the main camera or portal pose moves,
 * at full resolution, and show the procedural surface only when capture is
 * unavailable. All values can be overridden at launch for debugging/A/B tests:</p>
 *
 * <pre>
 * -Dcrythst.portal.render=false          disable portal rendering entirely
 * -Dcrythst.portal.capture=false         force the procedural fallback
 * -Dcrythst.portal.procedural=true       alias for forcing the fallback
 * -Dcrythst.portal.debugMask=true        draw the portal opening outline
 * -Dcrythst.portal.trace=true            one aggregated timing line per second
 * -Dcrythst.portal.recursion=1           max nested portal layers (1 = none)
 * -Dcrythst.portal.capture.scale=1.0     capture resolution scale (0.125..1]
 * -Dcrythst.portal.capture.dump=true     dump the first captured target
 * </pre>
 */
final class PortalCaptureSettings {
    static final boolean RENDER_ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("crythst.portal.render", "true"));
    static final boolean CAPTURE_ENABLED = RENDER_ENABLED
            && !"false".equalsIgnoreCase(System.getProperty("crythst.portal.capture", "true"));
    static final boolean FORCE_PROCEDURAL = !CAPTURE_ENABLED
            || Boolean.getBoolean("crythst.portal.procedural")
            || Boolean.getBoolean("crythst.portal.forceProcedural");
    static final boolean DEBUG_MASK = Boolean.getBoolean("crythst.portal.debugMask");
    static final boolean TRACE = Boolean.getBoolean("crythst.portal.trace");
    static final boolean DUMP_TARGET = Boolean.getBoolean("crythst.portal.capture.dump");
    static final int MAX_RECURSION = readInt("crythst.portal.recursion", 1, 0, 2);

    static final float SCALE = readScale();
    static final int MAX_WIDTH = 1920;
    static final int MAX_HEIGHT = 1080;
    /** One destination render per frame keeps the capture budget bounded. */
    static final int MAX_PORTALS_PER_ROUND = 1;
    static final int MAX_ACTIVE_TARGETS = 4;
    static final int SECTION_COMPILE_BUDGET = 8;
    static final double MAX_CAPTURE_DISTANCE = 128.0;
    static final double MAX_CAPTURE_DISTANCE_SQUARED =
            MAX_CAPTURE_DISTANCE * MAX_CAPTURE_DISTANCE;

    /** Capture immediately when the camera/portal moved. */
    static final long BASE_INTERVAL_NANOS = 0L;
    static final long MAX_INTERVAL_NANOS = 250_000_000L;
    /** Stationary refresh for destination entities/weather. */
    static final long IDLE_REFRESH_NANOS = 100_000_000L;
    static final long TIMING_LOG_INTERVAL_NANOS = 1_000_000_000L;

    /** Camera motion below this is considered unchanged for texture reuse. */
    static final double REUSE_DISTANCE_SQUARED = 1.0e-6; // 0.001 block
    static final float REUSE_ANGLE_DEGREES = 0.01f;
    static final long REUSE_MAX_AGE_NANOS = IDLE_REFRESH_NANOS;

    private PortalCaptureSettings() {
    }

    static int[] targetSize(RenderTarget mainTarget) {
        return PortalCameraMath.targetSize(
                mainTarget.width, mainTarget.height, SCALE, MAX_WIDTH, MAX_HEIGHT);
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

    private static int readInt(String property, int fallback, int min, int max) {
        final String raw = System.getProperty(property);
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
