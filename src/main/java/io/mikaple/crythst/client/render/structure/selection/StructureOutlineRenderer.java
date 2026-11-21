package io.mikaple.crythst.client.render.structure.selection;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Submits the oriented structure outline through Minecraft's own block-outline
 * submission method.
 *
 * <p>The mixin cancels the default {@code submitBlockOutline} call for a
 * structure voxel and invokes this class instead. The pose stack gets the
 * structure voxel's camera-relative pose, then the same block-local shape that
 * vanilla would submit goes through the special private
 * {@code submitHitOutline} method via an invoker. Render types, colours, line
 * width and the high-contrast branch therefore stay byte-for-byte vanilla;
 * only the pose differs.</p>
 */
public final class StructureOutlineRenderer {
    /**
     * Vanilla's high-contrast secondary outline arguments.
     * Mirrors {@code LevelRenderer#submitBlockOutline}.
     */
    private static final int HIGH_CONTRAST_COLOR = -16777216;
    private static final float HIGH_CONTRAST_WIDTH = 7.0F;
    private static final int HIGH_CONTRAST_PRIMARY_COLOR = -11010079;

    /**
     * Callback onto {@code LevelRenderer}'s private
     * {@code submitHitOutline} method.
     */
    public interface HitOutlineSubmitter {
        void crythst$submitHitOutline(PoseStack poseStack,
                                      SubmitNodeCollector submitNodeCollector,
                                      RenderType renderType,
                                      BlockOutlineRenderState state,
                                      int color,
                                      float width,
                                      boolean afterTerrain);
    }

    private StructureOutlineRenderer() {
    }

    /**
     * Applies the structure pose and submits the outline.
     *
     * @return {@code false} when there is no outline state to render
     */
    public static boolean submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                 LevelRenderState levelState, GameRenderer gameRenderer,
                                 StructureOutlineTarget target, HitOutlineSubmitter submitter) {
        final BlockOutlineRenderState state = levelState.blockOutlineRenderState;
        if (state == null) {
            return false;
        }

        final Vec3 camera = levelState.cameraRenderState.pos;
        final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                target.pose(), target.voxelX(), target.voxelY(), target.voxelZ(),
                target.blockScale(), new Vector3d(camera.x, camera.y, camera.z));

        poseStack.pushPose();
        try {
            poseStack.mulPose(matrix);

            if (state.highContrast()) {
                submitter.crythst$submitHitOutline(poseStack, submitNodeCollector,
                        RenderTypes.secondaryBlockOutline(), state,
                        HIGH_CONTRAST_COLOR, HIGH_CONTRAST_WIDTH, state.isTranslucent());
            }

            final int color = state.highContrast() ? HIGH_CONTRAST_PRIMARY_COLOR : ARGB.black(102);
            final RenderType renderType;
            if (state.highContrast()) {
                renderType = RenderTypes.linesDepthBias();
            } else if (gameRenderer.useImprovedTransparency()) {
                renderType = RenderTypes.linesTranslucentNoDepthWrite();
            } else {
                renderType = RenderTypes.linesTranslucent();
            }

            submitter.crythst$submitHitOutline(poseStack, submitNodeCollector, renderType, state, color,
                    gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth,
                    state.isTranslucent());
        } finally {
            poseStack.popPose();
        }
        return true;
    }
}
