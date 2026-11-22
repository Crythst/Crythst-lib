package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.client.render.portal.PortalRenderManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the bounded portal section selection during a portal extraction.
 *
 * <p>{@code LevelExtractor.applyFrustum} clears the visible-section lists and
 * delegates to vanilla's unbounded occlusion-graph frustum offset. Portal
 * captures already fill those lists with {@code PortalSectionVisibility}, so
 * applyFrustum must not replace or re-query them for the portal camera.</p>
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorFrustumMixin {
    @Inject(method = "applyFrustum", at = @At("HEAD"), cancellable = true)
    private void crythst$skipPortalFrustum(Frustum frustum, CallbackInfo ci) {
        if (PortalRenderManager.isDestinationExtracting()) {
            ci.cancel();
        }
    }
}
