package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only view of a structure's voxel grid used while meshing a section.
 *
 * <p>Lookups may cross section boundaries. Coordinates are absolute voxels in
 * the snapshot space; outside the snapshot both methods return air / false.</p>
 */
interface StructureVoxelView {
    boolean isOccluded(int x, int y, int z);

    BlockState stateAt(int x, int y, int z);
}
