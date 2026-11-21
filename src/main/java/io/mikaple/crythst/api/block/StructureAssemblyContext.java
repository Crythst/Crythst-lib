package io.mikaple.crythst.api.block;

import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.server.level.ServerLevel;

/**
 * Context passed to a block's assembly listener when a structure is built.
 *
 * @param level  server level that owns the structure
 * @param volume block grid being assembled; coordinates are structure-local
 *               voxels
 */
public record StructureAssemblyContext(ServerLevel level, VoxelVolume volume) {
    public StructureAssemblyContext {
        if (level == null) {
            throw new NullPointerException("level");
        }
        if (volume == null) {
            throw new NullPointerException("volume");
        }
    }
}
