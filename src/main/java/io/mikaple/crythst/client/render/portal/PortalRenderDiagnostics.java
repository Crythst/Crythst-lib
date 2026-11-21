package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Rate-limited, low-volume diagnostics for the portal view pipeline.
 *
 * <p>Render-thread logging is deliberately sparse: failures once, skipped
 * captures at most every few seconds, and timings at most once per second.</p>
 */
final class PortalRenderDiagnostics {
    private static final long SKIP_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private static boolean failureLogged;
    private static boolean firstCaptureLogged;
    private static boolean pathLogged;
    private static boolean dumped;
    private static long lastSkipLogNanos = -SKIP_LOG_INTERVAL_NANOS;

    private PortalRenderDiagnostics() {
    }

    static void failure(String message, Throwable error) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        Crythst.LOGGER.warn(message, error);
    }

    static void skipped(PortalSnapshot portal, String reason) {
        final long now = System.nanoTime();
        if (now - lastSkipLogNanos < SKIP_LOG_INTERVAL_NANOS) {
            return;
        }
        lastSkipLogNanos = now;
        Crythst.LOGGER.info("Crythst portal view skipped for '{}': {}",
                portal == null ? "<unknown>" : portal.name(), reason);
    }

    static void firstCapture(PortalSnapshot portal, PortalViewTarget target, int sections) {
        if (firstCaptureLogged) {
            return;
        }
        firstCaptureLogged = true;
        Crythst.LOGGER.info(
                "Crythst portal view captured for '{}' ({}x{}, {} sections)",
                portal.name(), target.width(), target.height(), sections);
    }

    static void path(String textureId) {
        if (pathLogged) {
            return;
        }
        pathLogged = true;
        Crythst.LOGGER.info("Crythst portal render path: offscreen texture '{}'", textureId);
    }

    static void timings(PortalSnapshot portal, PortalViewTarget target, int sections,
                         double extractMs, double renderMs, long intervalNanos) {
        Crythst.LOGGER.info(
                "[portal timings] portal='{}' {}x{} sections={} extract={}ms render={}ms "
                        + "total={}ms scale={} interval={}ms",
                portal.name(), target.width(), target.height(), sections,
                String.format("%.2f", extractMs),
                String.format("%.2f", renderMs),
                String.format("%.2f", extractMs + renderMs),
                String.format("%.2f", PortalCaptureSettings.SCALE),
                intervalNanos / 1_000_000L);
    }

    static void dumpIfEnabled(PortalViewTarget target) {
        if (!PortalCaptureSettings.DUMP_TARGET || dumped || target == null || target.target() == null) {
            return;
        }
        dumped = true;
        Screenshot.grab(Minecraft.getInstance().gameDirectory, target.target(),
                message -> Crythst.LOGGER.info("Crythst portal capture dump: {}",
                        message.getString()));
    }
}
