package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.joml.Vector3d;

/**
 * A merged axis-aligned box of voxels.
 */
public record VoxelBox(
        int minX, int minY, int minZ,
        int sizeX, int sizeY, int sizeZ,
        PhysicsMaterial material,
        double blockMass
) {
    public VoxelBox {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("Voxel box sizes must be positive");
        }
    }

    public int cellCount() {
        return sizeX * sizeY * sizeZ;
    }

    public double mass() {
        return cellCount() * blockMass;
    }

    /** Centre in unscaled voxel coordinates. */
    public Vector3d center() {
        return new Vector3d(
                minX + sizeX * 0.5,
                minY + sizeY * 0.5,
                minZ + sizeZ * 0.5
        );
    }

    public Vector3d size() {
        return new Vector3d(sizeX, sizeY, sizeZ);
    }
}
