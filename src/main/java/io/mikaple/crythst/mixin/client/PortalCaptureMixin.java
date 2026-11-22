package io.mikaple.crythst.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.mikaple.crythst.client.render.portal.PortalRenderManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.debug.GameTestBlockHighlightRenderer;
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

    // The portal temporarily sets a captured frustum to keep vanilla from
    // replacing its visible sections. Vanilla debug rendering would draw that
    // frustum as coloured polygons in the main view's gizmo collection.
    @WrapWithCondition(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;emitGizmos(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V"))
    private boolean crythst$emitMainDebugGizmos(DebugRenderer renderer, Frustum frustum,
                                              double x, double y, double z, float partialTick) {
        return !PortalRenderManager.isDestinationExtracting();
    }

    @WrapWithCondition(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/debug/GameTestBlockHighlightRenderer;emitGizmos()V"))
    private boolean crythst$emitMainGameTestGizmos(GameTestBlockHighlightRenderer renderer) {
        return !PortalRenderManager.isDestinationExtracting();
    }

    @WrapWithCondition(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractGizmos()V"))
    private boolean crythst$collectMainGizmos(LevelExtractor extractor) {
        return !PortalRenderManager.isDestinationExtracting();
    }

    @WrapWithCondition(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;flipUpdateTrackingSets()V"))
    private boolean crythst$preserveMainChunkUpdates(ClientChunkCache cache) {
        // The main render state still references the previous sets. Rotating
        // again would clear them before the main frame can apply its updates.
        return !PortalRenderManager.isDestinationExtracting();
    }
}
