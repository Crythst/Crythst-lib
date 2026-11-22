package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.client.render.portal.PortalRenderManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extracts portal destination states after the main camera's state is complete.
 *
 * <p>Running at {@code RETURN} is important: the main {@code LevelRenderState}
 * and the main visible-section list are fully populated, so each destination is
 * extracted into its own {@code LevelRenderState} without consuming the resets
 * or chunk-delta sets that belong to the main extraction. The nested extraction
 * is guarded by {@link PortalRenderManager#isDestinationExtracting()}.</p>
 */
@Mixin(LevelExtractor.class)
public abstract class PortalCaptureMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void crythst$extractPortalDestinations(DeltaTracker deltaTracker, Camera camera,
                                                   float worldPartialTicks, CallbackInfo ci) {
        PortalRenderManager.onExtractEnd(camera, deltaTracker, worldPartialTicks);
    }
}
