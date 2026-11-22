package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import io.mikaple.crythst.client.render.portal.PortalRenderManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the portal destination pass to the vanilla level framegraph.
 *
 * <p>{@code addMainPass} has just created the main colour+depth pass and stored
 * its current {@link ResourceHandle} in {@link LevelTargetBundle#main}. The
 * portal pass declares a read/write dependency on that same handle, so the
 * framegraph runs it after the vanilla main pass and before the frame is
 * presented. No separate colour target, texture copy or later quad submission
 * is involved.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class PortalLevelRendererMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(method = "addMainPass", at = @At("RETURN"))
    private void crythst$addPortalFramePass(FrameGraphBuilder frame,
                                            FeatureRenderDispatcher.PreparedFrame featureFrame,
                                            GpuBufferSlice terrainFog,
                                            ChunkSectionsToRender chunkSectionsToRender,
                                            boolean consistentDepthRequired,
                                            CallbackInfo ci) {
        this.targets.main = PortalRenderManager.addFramePass(frame, this.targets.main);
    }
}
