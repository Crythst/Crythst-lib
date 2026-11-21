package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of the voxel currently being mined, published by
 * {@link StructureMiningController} for the break-overlay renderer.
 *
 * <p>The snapshot carries the structure pose, voxel and scale so the crack
 * quads can be submitted with the same camera-relative transform as the
 * selection outline. It intentionally does not carry a real {@code BlockPos}:
 * using vanilla block destruction at an axis-aligned world position would be
 * wrong for rotated or scaled structures.</p>
 *
 * @param bodyId     structure body id
 * @param pose       structure-origin pose used by the hit
 * @param voxelX     structure-local voxel coordinate
 * @param voxelY     structure-local voxel coordinate
 * @param voxelZ     structure-local voxel coordinate
 * @param blockScale metres per voxel
 * @param state      visual state of the voxel
 * @param progress   destroy progress in {@code (0, 1]}
 */
public record StructureBreakProgress(
        long bodyId,
        Transform3d pose,
        int voxelX,
        int voxelY,
        int voxelZ,
        double blockScale,
        BlockState state,
        float progress
) {
    public StructureBreakProgress {
        if (pose == null) {
            throw new IllegalArgumentException("pose must not be null");
        }
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive and finite: " + blockScale);
        }
        if (!(progress > 0.0F)) {
            throw new IllegalArgumentException("progress must be positive: " + progress);
        }
    }

    /** Vanilla crack stage, or {@code -1} when nothing should render. */
    public int stage() {
        return MiningMath.stage(progress);
    }

    public static @Nullable StructureBreakProgress of(@Nullable StructureBlockHitResult hit,
                                                      float progress) {
        if (hit == null || !(progress > 0.0F)) {
            return null;
        }
        final BlockState state = hit.blockState();
        if (state == null || state.isAir()) {
            return null;
        }
        return new StructureBreakProgress(
                hit.bodyId(),
                hit.structurePose(),
                hit.voxelX(),
                hit.voxelY(),
                hit.voxelZ(),
                hit.blockScale(),
                state,
                progress
        );
    }
}
