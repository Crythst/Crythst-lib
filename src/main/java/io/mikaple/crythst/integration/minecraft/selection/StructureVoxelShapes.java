package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the exact vanilla shape of a structure voxel in structure-local
 * coordinates.
 *
 * <p>Selection and the block outline must agree on the geometry they describe.
 * Both therefore query {@link BlockState#getShape} through the same kind of
 * read-only {@link BlockGetter}: its coordinates are structure-local voxel
 * coordinates, so state properties (slabs, stairs, ...) are exact and
 * neighbour-sensitive shapes (fences, walls, ...) see the structure's own
 * neighbours instead of the real world underneath it.</p>
 *
 * <p>The returned shape is block-local ({@code [0, 1]^3}) and is never
 * {@code null}; it may be empty when the state has no outline shape.</p>
 */
public final class StructureVoxelShapes {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private StructureVoxelShapes() {
    }

    /**
     * Shape of one structure voxel using the empty collision context.
     */
    public static VoxelShape localShape(StructureVoxelView view, int x, int y, int z) {
        return localShape(view, x, y, z, CollisionContext.empty());
    }

    /**
     * Shape of one structure voxel.
     *
     * @param view    structure view that supplies the block states and bounds
     * @param x       structure-local voxel coordinate
     * @param y       structure-local voxel coordinate
     * @param z       structure-local voxel coordinate
     * @param context vanilla collision context, or {@code null} for the empty
     *                context
     * @return the block-local shape, never {@code null}
     */
    public static VoxelShape localShape(StructureVoxelView view, int x, int y, int z,
                                        @Nullable CollisionContext context) {
        if (view == null) {
            return Shapes.empty();
        }
        final BlockState state = state(view, x, y, z);
        if (state.isAir()) {
            return Shapes.empty();
        }
        final CollisionContext effective = context == null ? CollisionContext.empty() : context;
        return state.getShape(new LocalBlockGetter(view), new BlockPos(x, y, z), effective);
    }

    private static BlockState state(StructureVoxelView view, int x, int y, int z) {
        final IntBounds3 bounds = view.bounds();
        if (bounds != null && !bounds.contains(x, y, z)) {
            return AIR;
        }
        final BlockState state = view.blockState(x, y, z);
        return state == null ? AIR : state;
    }

    /**
     * Read-only view of the structure grid. Coordinates are structure-local
     * voxel coordinates, so neighbour queries performed by
     * {@link BlockState#getShape} stay inside the structure.
     */
    private static final class LocalBlockGetter implements BlockGetter {
        private final StructureVoxelView view;

        private LocalBlockGetter(StructureVoxelView view) {
            this.view = view;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state(view, pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            final IntBounds3 bounds = view.bounds();
            return bounds == null ? 0 : bounds.sizeY();
        }

        @Override
        public int getMinY() {
            final IntBounds3 bounds = view.bounds();
            return bounds == null ? 0 : bounds.minY();
        }
    }
}
