package io.mikaple.crythst.api.block;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block-cell change inside a live physics structure.
 *
 * <p>Coordinates are structure-local voxels. Removed cells report an air state
 * rather than {@code null}. The event is fired after the collider has been
 * rebuilt and before the structure's block-change snapshot is updated.</p>
 *
 * @param structure structure whose volume changed
 * @param x         structure-local X voxel
 * @param y         structure-local Y voxel
 * @param z         structure-local Z voxel
 * @param oldState  previous block state, or air when the cell was empty
 * @param newState  new block state, or air when the cell was removed
 */
public record StructureBlockChange(
        PhysicsStructure structure,
        int x, int y, int z,
        BlockState oldState,
        BlockState newState
) {
    public StructureBlockChange {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (oldState == null || newState == null) {
            throw new NullPointerException("block state");
        }
    }
}
