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
        PortalRenderManager.register();
        if (Boolean.getBoolean("crythst.portal.fade")) {
            // The geometrically correct path needs no curtain. Kept as an
            // opt-in debug aid for command/respawn jumps.
            PortalTeleportFade.register();
        }
        LevelRenderEvents.BEFORE_GIZMOS.register(PortalSurfaceRenderer::render);
        Crythst.LOGGER.info(
                "Crythst portal renderer registered (virtual destination camera + view texture, "
                        + "oblique exit-plane clip)");
        if (PortalCaptureSettings.FORCE_PROCEDURAL) {
            Crythst.LOGGER.info(
                    "Crythst portal debug: procedural surface forced (capture disabled)");
        }
    }
}
