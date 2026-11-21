package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

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
    private static long lastSurfaceLogNanos = -SKIP_LOG_INTERVAL_NANOS;

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

    /**
     * One-line surface diagnostic (max 1/s): texture id, pipeline, vertex
     * count, UV range and whether the quad was submitted.
     */
    static void surface(PortalSnapshot portal, Identifier textureId, RenderType type,
                        int vertices, float minU, float maxU, float minV, float maxV,
                        boolean submitted, String detail) {
        final long now = System.nanoTime();
        if (now - lastSurfaceLogNanos < SKIP_LOG_INTERVAL_NANOS) {
            return;
        }
        lastSurfaceLogNanos = now;

        boolean textureViewOk = false;
        int textureW = -1;
        int textureH = -1;
        String sampler = "<none>";
        if (textureId != null) {
            try {
                final var texture = Minecraft.getInstance().getTextureManager().getTexture(textureId);
                textureViewOk = texture.getTextureView() != null;
                textureW = texture.getTextureView().getWidth(0);
                textureH = texture.getTextureView().getHeight(0);
                sampler = String.valueOf(texture.getSampler());
            } catch (Throwable ignored) {
                textureViewOk = false;
            }
        }
        final String pipeline = type == null ? "<none>"
                : String.valueOf(type.pipeline().getLocation());
        final boolean viewMatches = portal != null
                && PortalRenderManager.textureViewMatches(portal.id());
        Crythst.LOGGER.info(
                "Crythst portal surface: portal='{}' submitted={} detail={} texture={} "
                        + "textureView={}x{} viewOk={} viewMatchesTarget={} sampler={} "
                        + "pipeline={} vertices={} uv=[{},{}..{},{}]",
                portal == null ? "<none>" : portal.name(), submitted, detail,
                textureId == null ? "<procedural>" : textureId.toString(),
                textureW, textureH, textureViewOk, viewMatches, sampler, pipeline, vertices,
                String.format("%.3f", minU), String.format("%.3f", maxU),
                String.format("%.3f", minV), String.format("%.3f", maxV));
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
