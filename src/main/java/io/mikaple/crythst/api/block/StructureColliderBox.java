package io.mikaple.crythst.api.block;

import org.joml.Vector3d;

/**
 * An axis-aligned collision box expressed relative to the lower corner of one
 * block.
 *
 * <p>Coordinates are in block-local units: {@code (0, 0, 0)} is the lower
 * north-west corner of the block and {@code (1, 1, 1)} is the opposite corner.
 * Out-of-range values are allowed for blocks whose physical shape deliberately
 * extends beyond their voxel, but most implementations should stay inside
 * {@code [0, 1]}.</p>
 *
 * @param minX lower X corner
 * @param minY lower Y corner
 * @param minZ lower Z corner
 * @param maxX upper X corner
 * @param maxY upper Y corner
 * @param maxZ upper Z corner
 */
public record StructureColliderBox(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
) {
    public StructureColliderBox {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
            throw new IllegalArgumentException("Collider box coordinates must be finite");
        }
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            throw new IllegalArgumentException("Collider box must have positive extents");
        }
    }

    /** A full one-metre cube. */
    public static StructureColliderBox fullBlock() {
        return new StructureColliderBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
    }

    /** A centred box with the given extents. */
    public static StructureColliderBox centered(double sizeX, double sizeY, double sizeZ) {
        return new StructureColliderBox(
                (1.0 - sizeX) * 0.5, (1.0 - sizeY) * 0.5, (1.0 - sizeZ) * 0.5,
                (1.0 + sizeX) * 0.5, (1.0 + sizeY) * 0.5, (1.0 + sizeZ) * 0.5);
    }

    public double sizeX() {
        return maxX - minX;
    }

    public double sizeY() {
        return maxY - minY;
    }

    public double sizeZ() {
        return maxZ - minZ;
    }

    public Vector3d center() {
        return new Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
    }
}
