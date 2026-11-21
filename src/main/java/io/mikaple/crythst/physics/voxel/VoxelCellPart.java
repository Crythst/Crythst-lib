package io.mikaple.crythst.physics.voxel;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A piece of collision geometry inside one voxel cell.
 *
 * <p>Coordinates are structure-local voxel units, not metres. A part therefore
 * normally spans values in {@code [x, x + 1]} for the cell whose lower corner is
 * {@code (x, y, z)}. The collider builder scales parts by
 * {@link VoxelColliderOptions#blockScale()} exactly like ordinary voxel boxes.</p>
 *
 * @param minX lower X corner in voxel coordinates
 * @param minY lower Y corner in voxel coordinates
 * @param minZ lower Z corner in voxel coordinates
 * @param sizeX extent along X in voxels
 * @param sizeY extent along Y in voxels
 * @param sizeZ extent along Z in voxels
 */
public record VoxelCellPart(
        double minX, double minY, double minZ,
        double sizeX, double sizeY, double sizeZ
) {
    public VoxelCellPart {
        if (!finite(minX) || !finite(minY) || !finite(minZ)
                || !finite(sizeX) || !finite(sizeY) || !finite(sizeZ)) {
            throw new IllegalArgumentException("Voxel part coordinates must be finite");
        }
        if (sizeX <= 0.0 || sizeY <= 0.0 || sizeZ <= 0.0) {
            throw new IllegalArgumentException("Voxel part sizes must be positive");
        }
    }

    public Vector3d center() {
        return new Vector3d(minX + sizeX * 0.5, minY + sizeY * 0.5, minZ + sizeZ * 0.5);
    }

    public Vector3d center(Vector3d dest) {
        return dest.set(minX + sizeX * 0.5, minY + sizeY * 0.5, minZ + sizeZ * 0.5);
    }

    /**
     * Volume in cubic voxels. Mass is distributed over parts in proportion to
     * this value.
     */
    public double volume() {
        return sizeX * sizeY * sizeZ;
    }

    public VoxelCellPart offset(double dx, double dy, double dz) {
        return new VoxelCellPart(minX + dx, minY + dy, minZ + dz, sizeX, sizeY, sizeZ);
    }

    public boolean contains(Vector3dc point) {
        return point.x() >= minX && point.x() <= minX + sizeX
                && point.y() >= minY && point.y() <= minY + sizeY
                && point.z() >= minZ && point.z() <= minZ + sizeZ;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
