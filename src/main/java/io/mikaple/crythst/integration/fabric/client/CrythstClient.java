package io.mikaple.crythst.integration.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderManager;
import io.mikaple.crythst.client.render.structure.batch.BatchedStructureRenderer;
import io.mikaple.crythst.client.render.item.CrythstItemSpecialRenderers;
import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureInteraction;
import io.mikaple.crythst.integration.fabric.network.StructureSync;
import io.mikaple.crythst.client.render.portal.PortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * Client entrypoint for Crythst's physics validation overlays.
 *
 * <p>Registers the client-tick snapshot refresh, the solid block-form renderer,
 * the wireframe gizmo renderer, and the F9 toggle for solid blocks. All of
 * these are diagnostic only and do not affect the simulation.</p>
 */
public final class CrythstClient implements ClientModInitializer {
    private static final KeyMapping TOGGLE_BLOCKS = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.crythst.toggle_blocks",
                    InputConstants.Type.KEYBOARD, InputConstants.KEY_F9,
                    KeyMapping.Category.DEBUG));

    @Override
    public void onInitializeClient() {
        CrythstItemSpecialRenderers.register();
        SpecialBlockRenderManager.register();
        BatchedStructureRenderer.register();
        ClientStructureInteraction.register();
        StructureSync.registerClient();
        PortalRenderer.register();
        ClientTickEvents.END_CLIENT_TICK.register(ClientPhysicsView::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_BLOCKS.consumeClick()) {
                final boolean enabled = StructureBlockRenderer.toggleBlockRendering();
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal(
                            "Crythst solid blocks " + (enabled ? "enabled" : "disabled")));
                }
            }
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(StructureBlockRenderer::render);
        Crythst.LOGGER.info("Crythst client renderers registered "
                + "(structures, loose bodies, selection, debug gizmos)");
    }
}
