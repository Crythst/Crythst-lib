package io.mikaple.crythst.integration.minecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Floor-height helper that works with a {@link StructurePathProbe}.
 *
 * <p>This mirrors {@code WalkNodeEvaluator.getFloorLevel(BlockGetter,
 * BlockPos)}. The important part is passing the structure-aware getter to
 * {@link BlockState#getCollisionShape(BlockGetter, BlockPos)}: slabs, stairs,
 * fences and walls then resolve their shape against structure neighbours
 * rather than the host world's air/terrain.</p>
 */
public final class StructurePathFloor {
    private StructurePathFloor() {
    }

    public static double floorLevel(BlockGetter level, BlockPos pos) {
        final BlockPos below = pos.below();
        final VoxelShape shape = level.getBlockState(below)
                .getCollisionShape(level, below);
        return below.getY() + (shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y));
    }
}
