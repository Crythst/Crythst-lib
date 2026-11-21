package io.mikaple.crythst.api.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Supplies an alternative collision shape for a block while it is part of a
 * Crythst physics structure.
 *
 * <p>Implement this on a {@code Block} class, or register it through
 * {@link StructureBlockHooks}. The shape is baked on the thread that creates or
 * rebuilds the structure (normally the server thread) and is also used for mass
 * and inertia. It does not change vanilla collision outside structures.</p>
 *
 * <p>Precedence: an explicit registry entry overrides this interface; when no
 * registry entry exists, this interface overrides the normal one-voxel cube.
 * A registered {@link BlockStructureDynamicCollider} takes precedence over this
 * shape for the same block.</p>
 */
@FunctionalInterface
public interface BlockStructureCollisionShape {

    /**
     * Returns the collision shape in block-local coordinates.
     *
     * @param level the level containing the block, or {@code null} when the
     *              structure was restored from a snapshot without a live level
     * @param pos   block position; for structures without a live level this is
     *              the structure-local voxel position
     * @param state block state being baked
     * @return a non-null shape; an empty shape keeps the block's mass but
     *         removes its collision geometry. If every solid cell ends up with
     *         no geometry, structure creation fails because a rigid body needs
     *         a collision shape.
     */
    VoxelShape structureCollisionShape(@Nullable BlockGetter level, BlockPos pos, BlockState state);
}
