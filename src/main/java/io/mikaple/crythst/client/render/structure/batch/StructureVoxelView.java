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

    /**
     * Whether the cell is a whole opaque cube ({@code isSolidRender()}).
     *
     * <p>Unlike {@link #isOccluded}, which follows {@code canOcclude()} for AO,
     * this is exact for the enclosed-block test: stairs, slabs, glass and
     * other shaped blocks are not allowed to hide an exposed face. Bodies
     * override it with a cached per-body mask; the default keeps test views
     * simple and conservative.</p>
     */
    default boolean isFullyOpaque(int x, int y, int z) {
        final BlockState state = stateAt(x, y, z);
        return state != null && state.isSolidRender();
    }
}
