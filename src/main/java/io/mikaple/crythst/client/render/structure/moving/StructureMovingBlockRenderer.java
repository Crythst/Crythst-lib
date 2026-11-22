package io.mikaple.crythst.client.render.structure.moving;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineTransform;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders piston moving blocks that live inside a physics structure.
 *
 * <p>{@code moving_piston} has no block model; vanilla draws it through
 * {@code PistonMovingBlockEntityRenderer} using the moved state, progress and
 * direction. A batched section mesh cannot represent that transient,
 * fractional translation. This renderer submits the same vanilla moving-block
 * feature at the structure voxel's camera-relative pose, offset by the piston
 * progress, so rotating/scaled structures keep a correct moving block instead
 * of an invisible or statically baked block.</p>
 */
public final class StructureMovingBlockRenderer {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureMovingBlockRenderer() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LevelRenderEvents.COLLECT_SUBMITS.register(StructureMovingBlockRenderer::collect);
        }
    }

    private static void collect(LevelRenderContext context) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null || context.levelState() == null
                || context.levelState().cameraRenderState == null
                || context.levelState().cameraRenderState.pos == null
                || context.poseStack() == null
                || context.submitNodeCollector() == null) {
            return;
        }
        final Vec3 camera = context.levelState().cameraRenderState.pos;
        final Vector3d cameraPos = new Vector3d(camera.x, camera.y, camera.z);
        final long now = System.nanoTime();

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final StructureRenderState render = structure.renderStateAt(now);
            final StructureBlockSnapshots.Snapshot snapshot =
                    StructureBlockSnapshots.get(level.dimension(), render.bodyId());
            if (snapshot == null || snapshot.movingBlocks().isEmpty()) {
                continue;
            }
            for (StructureBlockSnapshots.MovingBlock moving : snapshot.movingBlocks()) {
                if (moving.state() == null || moving.state().isAir()) {
                    continue;
                }
                final double scale = render.blockScale();
                final Vector3d worldCenter = render.pose().transformPoint(new Vector3d(
                        (moving.x() + 0.5) * scale,
                        (moving.y() + 0.5) * scale,
                        (moving.z() + 0.5) * scale));
                final BlockPos tintPos = BlockPos.containing(
                        worldCenter.x, worldCenter.y, worldCenter.z);

                final MovingBlockRenderState state = new MovingBlockRenderState();
                state.blockState = moving.state();
                state.blockPos = tintPos;
                state.randomSeedPos = tintPos;
                state.biome = level.getBiome(tintPos);
                state.cardinalLighting = level.cardinalLighting();
                state.lightEngine = level.getLightEngine();

                final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                        render.pose(), moving.x(), moving.y(), moving.z(),
                        scale, cameraPos);
                final PoseStack poseStack = context.poseStack();
                poseStack.pushPose();
                try {
                    poseStack.mulPose(matrix);
                    final Direction direction = moving.direction();
                    final float progress = moving.progress();
                    // The matrix already contains the block scale, so the
                    // piston offset is expressed in block-local units.
                    poseStack.translate(
                            direction.getStepX() * progress,
                            direction.getStepY() * progress,
                            direction.getStepZ() * progress);
                    context.submitNodeCollector().submitMovingBlock(poseStack, state, 0);
                } finally {
                    poseStack.popPose();
                }
            }
        }
    }
}
