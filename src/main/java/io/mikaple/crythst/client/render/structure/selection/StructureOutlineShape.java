package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureVoxelView;
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
 * Resolves the visual block shape for a structure voxel.
 *
 * <p>The block state's shape is queried in structure-local voxel coordinates
 * through a small read-only {@link BlockGetter}. That makes state properties
 * (stairs, slabs, ...) exact and also lets neighbour-sensitive shapes such as
 * fences and walls see the structure's own neighbouring voxels instead of the
 * real world underneath the structure.</p>
 *
 * <p>The returned shape is block-local ({@code [0, 1]^3}). The outline
 * renderer applies the structure pose and {@code blockScale} separately.</p>
 */
public final class StructureOutlineShape {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @FunctionalInterface
    private interface StateLookup {
        BlockState at(int x, int y, int z);
    }

    private record StateSource(IntBounds3 bounds, StateLookup lookup) {
        BlockState state(int x, int y, int z) {
            if (!bounds.contains(x, y, z)) {
                return AIR;
            }
            final BlockState state = lookup.at(x, y, z);
            return state == null ? AIR : state;
        }
    }

    private StructureOutlineShape() {
    }

    /**
     * @param target   structure voxel to outline
     * @param fallback level used only when the structure's block states are not
     *                 available on the client
     * @param context  vanilla collision context
     * @return the block-local visual shape, never empty
     */
    public static VoxelShape localShape(StructureOutlineTarget target, BlockGetter fallback,
                                        CollisionContext context) {
        final StateSource source = sourceFor(target.bodyId());
        BlockState state = target.state();
        if ((state == null || state.isAir()) && source != null) {
            state = source.state(target.voxelX(), target.voxelY(), target.voxelZ());
        }
        if (state == null || state.isAir()) {
            return Shapes.block();
        }

        final BlockGetter getter = source == null ? fallback : new LocalBlockGetter(source);
        final VoxelShape shape = state.getShape(getter,
                new BlockPos(target.voxelX(), target.voxelY(), target.voxelZ()), context);
        return shape.isEmpty() ? Shapes.block() : shape;
    }

    /**
     * Finds the structure view that matches the body id. The client selection
     * views already cover both integrated-server structures and multiplayer
     * snapshots at the current render time.
     */
    private static @Nullable StateSource sourceFor(long bodyId) {
        for (StructureVoxelView view : ClientStructureSelection.views()) {
            if (view == null || view.closed() || view.bodyId() != bodyId) {
                continue;
            }
            final IntBounds3 bounds = view.bounds();
            if (bounds == null) {
                continue;
            }
            return new StateSource(bounds, view::blockState);
        }
        return null;
    }

    /**
     * Read-only view of the structure grid. Coordinates are structure-local
     * voxel coordinates, so neighbour queries performed by
     * {@link BlockState#getShape} stay inside the structure.
     */
    private static final class LocalBlockGetter implements BlockGetter {
        private final StateSource source;

        private LocalBlockGetter(StateSource source) {
            this.source = source;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return source.state(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return source.bounds().sizeY();
        }

        @Override
        public int getMinY() {
            return source.bounds().minY();
        }
    }
}
