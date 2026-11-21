package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;

/**
 * Read-only block grid consumed by the voxel collider builder.
 *
 * <p>Coordinates are structure-local integer voxels. The default block scale is
 * one metre per voxel.</p>
 */
public interface VoxelVolume {
    IntBounds3 bounds();

    boolean isSolid(int x, int y, int z);

    /**
     * Mass of one solid voxel in kilograms.
     */
    default double blockMass(int x, int y, int z) {
        return 1000.0;
    }

    default PhysicsMaterial blockMaterial(int x, int y, int z) {
        return PhysicsMaterial.DEFAULT;
    }
}
