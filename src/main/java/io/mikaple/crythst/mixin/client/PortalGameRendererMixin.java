package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.mikaple.crythst.client.render.portal.PortalRenderManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects the main render target while a portal destination is being
 * captured.
 *
 * <p>{@code LevelRenderer.render} always imports
 * {@code GameRenderer.mainRenderTarget()} into its framegraph. Overriding that
 * target for the duration of one synchronous capture call renders the
 * destination directly into the portal's offscreen texture (including its own
 * depth buffer), instead of rendering into the window and copying the colour
 * afterwards. That removes an ordering-sensitive copy and guarantees the
 * texture contains exactly the destination scene.</p>
 */
@Mixin(GameRenderer.class)
public abstract class PortalGameRendererMixin {
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void crythst$usePortalTarget(CallbackInfoReturnable<RenderTarget> cir) {
        final RenderTarget override = PortalRenderManager.renderTargetOverride();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
