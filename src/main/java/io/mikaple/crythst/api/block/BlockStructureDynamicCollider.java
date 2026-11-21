package io.mikaple.crythst.api.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Builds a block's collision geometry from one or more fractional boxes.
 *
 * <p>Use this instead of {@link BlockStructureCollisionShape} when the shape is
 * easier to express as local boxes (slabs, posts, machinery hitboxes) or when
 * it depends on block state without needing a full {@code VoxelShape}. The
 * method is called when a structure is created or rebuilt; it is not a per-tick
 * callback.</p>
 *
 * <p>Precedence: an explicit registry entry overrides this interface, and this
 * interface overrides {@link BlockStructureCollisionShape}. Returning an empty
 * list keeps the block's mass but gives it no collision geometry.</p>
 */
@FunctionalInterface
public interface BlockStructureDynamicCollider {

    /**
     * @param level the level containing the block, or {@code null} when the
     *              structure is backed by a saved volume without a live level
     * @param pos   block position or structure-local voxel position
     * @param state block state being baked
     * @return boxes relative to the block's lower corner; an empty list removes
     *         collision geometry while keeping mass. If every solid cell ends
     *         up with no geometry, structure creation fails because a rigid
     *         body needs a collision shape.
     */
    List<StructureColliderBox> structureCollisionBoxes(@Nullable BlockGetter level,
                                                       BlockPos pos, BlockState state);
}
