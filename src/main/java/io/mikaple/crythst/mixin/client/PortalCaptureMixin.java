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
 * Captures portal destination views <em>before</em> the main camera's level
 * state is extracted.
 *
 * <p>Running at {@code HEAD} means the nested extraction for the portal camera
 * can fill the shared {@code LevelRenderState} and be rendered immediately;
 * the outer invocation then resets and fills the same state for the main
 * camera as usual. Running at {@code END_EXTRACTION} (the previous approach)
 * was too late: the state had already been extracted for the main camera, so a
 * second {@code LevelRenderer.render} could not draw the destination's
 * entities/sections correctly.</p>
 */
@Mixin(LevelExtractor.class)
public abstract class PortalCaptureMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void crythst$capturePortalViews(DeltaTracker deltaTracker, Camera camera,
                                            float worldPartialTicks, CallbackInfo ci) {
        PortalRenderManager.onExtractStart(camera, deltaTracker, worldPartialTicks);
    }
}
