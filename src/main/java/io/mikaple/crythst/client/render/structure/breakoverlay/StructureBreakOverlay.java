package io.mikaple.crythst.client.render.structure.breakoverlay;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineTransform;
import io.mikaple.crythst.integration.minecraft.interaction.client.StructureBreakProgress;
import io.mikaple.crythst.integration.minecraft.interaction.client.StructureMiningController;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Structure-aware vanilla crack overlay.
 *
 * <p>Vanilla's {@code blockDestruction} animation is keyed by an axis-aligned
 * real {@code BlockPos}. A physics-structure voxel can be rotated, scaled,
 * split from a body or overlap an unrelated real block, so feeding the real
 * position to {@code ClientLevel#destroyBlockProgress} would draw a crack in
 * the wrong place (and could disturb the real block's own break animation).</p>
 *
 * <p>Instead this overlay reuses vanilla's own breaking-model submission in
 * {@link LevelRenderEvents#COLLECT_SUBMITS}: it collects the same
 * {@link BlockStateModelPart}s vanilla collects for the state, applies the
 * structure voxel's camera-relative pose (rotation and {@code blockScale}) via
 * {@link StructureOutlineTransform}, and calls
 * {@code SubmitNodeCollector#submitBreakingBlockModel} with the current crack
 * stage. The render type, depth behaviour and crack texture therefore stay
 * vanilla; only the transform is structure-aware.</p>
 *
 * <p>States with {@link RenderShape#INVISIBLE} (for example chests or signs
 * with dedicated block-entity renderers) cannot use the block model overlay and
 * are skipped. Their progress still shows through the held-item/arm swing and
 * the normal break particles on completion.</p>
 */
public final class StructureBreakOverlay {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureBreakOverlay() {
    }

    /** Registers the level submit hook. Safe to call more than once. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LevelRenderEvents.COLLECT_SUBMITS.register(StructureBreakOverlay::collect);
        }
    }

    private static void collect(LevelRenderContext context) {
        final StructureBreakProgress snapshot = StructureMiningController.currentProgress();
        if (snapshot == null) {
            return;
        }
        final int stage = snapshot.stage();
        if (stage < 0) {
            return;
        }
        final BlockState state = snapshot.state();
        if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getModelManager() == null) {
            return;
        }
        final BlockStateModel model;
        try {
            model = minecraft.getModelManager().getBlockStateModelSet().get(state);
        } catch (RuntimeException ignored) {
            return;
        }
        if (model == null) {
            return;
        }

        final List<BlockStateModelPart> parts = new ArrayList<>();
        final RandomSource random = RandomSource.create(
                state.getSeed(new BlockPos(snapshot.voxelX(), snapshot.voxelY(), snapshot.voxelZ())));
        try {
            model.collectParts(random, parts);
        } catch (RuntimeException ignored) {
            return;
        }
        if (parts.isEmpty()) {
            return;
        }

        final Vec3 camera = context.levelState().cameraRenderState.pos;
        final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                snapshot.pose(), snapshot.voxelX(), snapshot.voxelY(), snapshot.voxelZ(),
                snapshot.blockScale(), new Vector3d(camera.x, camera.y, camera.z));

        final PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        try {
            poseStack.mulPose(matrix);
            context.submitNodeCollector().submitBreakingBlockModel(
                    poseStack, List.copyOf(parts), stage, model.hasMaterialFlag(1));
        } finally {
            poseStack.popPose();
        }
    }
}
