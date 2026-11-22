package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.Crythst;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Client entry point for portal rendering.
 *
 * <p>This facade only wires lifecycle and render hooks; the actual pipeline is
 * split across {@link PortalRenderManager} (orchestration and capture
 * scheduling), the per-portal view registry/target, the destination extractor,
 * {@link PortalRenderPass} (RenderPearl destination render) and
 * {@link PortalSurfaceRenderer}. Call once from the client initializer.</p>
 */
public final class PortalRenderer {
    private static volatile boolean registered;

    private PortalRenderer() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        io.mikaple.crythst.integration.fabric.portal.PortalSyncClient.register();
        PortalClientView.register();
        PortalCrossingController.register();
        PortalRenderManager.register();
        if (Boolean.getBoolean("crythst.portal.fade")) {
            // The continuous crossing path needs no curtain. Kept as an
            // opt-in debug aid for cross-dimension respawn/command jumps.
            PortalTeleportFade.register();
        }
        LevelRenderEvents.BEFORE_GIZMOS.register(PortalSurfaceRenderer::render);
        Crythst.LOGGER.info(
                "Crythst portal renderer registered (same-FBO destination pass, "
                        + "per-pixel mask, exit-plane clip, recursion {})",
                PortalCaptureSettings.MAX_RECURSION);
        if (PortalCaptureSettings.OFFSCREEN_FALLBACK) {
            Crythst.LOGGER.warn(
                    "Crythst portal debug: legacy offscreen fallback was requested, "
                            + "but the seamless same-FBO pass is inactive");
        }
    }
}
