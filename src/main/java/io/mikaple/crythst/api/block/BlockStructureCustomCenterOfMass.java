package io.mikaple.crythst.api.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/**
 * Overrides the centre of mass of a block while it is part of a Crythst
 * physics structure.
 *
 * <p>Implement this on a {@code Block} class, or register it through
 * {@link StructureBlockHooks}. The returned vector is relative to the block's
 * lower north-west corner, in block units; the default centre is
 * {@code (0.5, 0.5, 0.5)}. Masses are weighted across the whole structure, so a
 * single heavy block can shift the body's centre of mass.</p>
 *
 * <p>An explicit registry entry overrides this interface. Custom centres are
 * not greedy-merged with neighbouring cells, but the block still contributes a
 * normal one-metre collision cube unless a collision-shape hook supplies
 * different geometry.</p>
 */
@FunctionalInterface
public interface BlockStructureCustomCenterOfMass {

    /**
     * @param level the level containing the block, or {@code null} for a
     *              restored/snapshot-backed structure
     * @param pos   block position or structure-local voxel position
     * @param state block state being baked
     * @return a finite offset from the block's lower corner, normally inside
     *         {@code [0, 1]} on each axis
     */
    Vector3dc structureCenterOfMass(@Nullable BlockGetter level, BlockPos pos, BlockState state);
}
