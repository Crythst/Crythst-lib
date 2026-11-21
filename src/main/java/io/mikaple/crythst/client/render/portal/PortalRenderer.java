package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.Crythst;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Client entry point for portal rendering.
 *
 * <p>This facade only wires lifecycle and render hooks; the actual pipeline is
 * split across {@link PortalRenderManager} (orchestration), the per-portal
 * view registry/target, the destination extractor, the offscreen pass and
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
        PortalClientView.register();
        PortalRenderManager.register();
        PortalTeleportFade.register();
        LevelRenderEvents.BEFORE_GIZMOS.register(PortalSurfaceRenderer::render);
        Crythst.LOGGER.info(
                "Crythst portal renderer registered (per-portal offscreen view + procedural fallback)");
        if (PortalCaptureSettings.FORCE_PROCEDURAL) {
            Crythst.LOGGER.info(
                    "Crythst portal debug: procedural surface forced (capture A/B mode)");
        }
    }
}
