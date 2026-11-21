package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineRenderer;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineTarget;
import io.mikaple.crythst.client.render.structure.selection.StructureSelectionOutline;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's axis-aligned outline submission for structure voxels.
 *
 * <p>The extraction handler publishes a {@link StructureOutlineTarget} together
 * with the {@code BlockOutlineRenderState} it installed. This injector
 * recognises only that state (by identity), applies the structure voxel's real
 * pose and then delegates to {@code LevelRenderer#submitHitOutline} through
 * {@link LevelRendererOutlineInvoker}. Real-block outlines never match, so
 * their rendering path is unchanged.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class StructureBlockOutlineMixin {
    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
    private void crythst$submitStructureOutline(PoseStack poseStack,
                                                SubmitNodeCollector submitNodeCollector,
                                                LevelRenderState levelRenderState,
                                                CallbackInfo callback) {
        final StructureOutlineTarget target =
                StructureSelectionOutline.publishedTargetFor(levelRenderState.blockOutlineRenderState);
        if (target == null) {
            return;
        }
        final LevelRendererOutlineInvoker invoker = (LevelRendererOutlineInvoker) (Object) this;
        if (StructureOutlineRenderer.submit(poseStack, submitNodeCollector, levelRenderState,
                this.gameRenderer, target, invoker)) {
            callback.cancel();
        }
    }
}
