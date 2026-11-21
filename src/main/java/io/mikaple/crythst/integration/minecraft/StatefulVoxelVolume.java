package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A {@link VoxelVolume} that also knows the Minecraft block state for each
 * voxel. This lets restored/persisted structures copy real blocks (and create
 * the matching block entities) into the auxiliary ticking level.
 */
public interface StatefulVoxelVolume extends VoxelVolume {
    /**
     * @return the block state at the voxel, or {@code null} when unavailable
     */
    BlockState blockState(int x, int y, int z);
}
