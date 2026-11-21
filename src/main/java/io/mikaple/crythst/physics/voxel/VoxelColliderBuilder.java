package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a solid voxel grid into merged boxes plus exact mass properties.
 *
 * <p>The merge is a three-axis greedy sweep: grow along X, then Z, then Y. It
 * produces far fewer Box2d-style children than one box per block while keeping
 * the collision surface identical for axis-aligned blocks.</p>
 */
public final class VoxelColliderBuilder {
    private VoxelColliderBuilder() {
    }

    public static VoxelColliderData build(VoxelVolume volume) {
        return build(volume, VoxelColliderOptions.DEFAULT);
    }

    public static VoxelColliderData build(VoxelVolume volume, VoxelColliderOptions options) {
        final IntBounds3 bounds = volume.bounds();
        final long cellCount = bounds.volume();
        if (cellCount > options.maxCells()) {
            throw new IllegalArgumentException("Voxel volume is too large to colliderise: "
                    + cellCount + " cells (limit " + options.maxCells() + ")");
        }

        final int sizeX = bounds.sizeX();
        final int sizeZ = bounds.sizeZ();
        final boolean[] visited = new boolean[(int) cellCount];
        final List<VoxelBox> boxes = new ArrayList<>();

        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final int index = index(bounds, x, y, z);
                    if (visited[index] || !volume.isSolid(x, y, z)) {
                        continue;
                    }

                    final PhysicsMaterial material = volume.blockMaterial(x, y, z);
                    final double blockMass = volume.blockMass(x, y, z);

                    if (!options.greedyMerge()) {
                        visited[index] = true;
                        boxes.add(new VoxelBox(x, y, z, 1, 1, 1, material, blockMass));
                        continue;
                    }

                    int extendX = 1;
                    while (x + extendX < bounds.maxX()
                            && matches(volume, visited, bounds, options, x + extendX, y, z, material, blockMass)) {
                        extendX++;
                    }

                    int extendZ = 1;
                    growZ:
                    while (z + extendZ < bounds.maxZ()) {
                        for (int dx = 0; dx < extendX; dx++) {
                            if (!matches(volume, visited, bounds, options, x + dx, y, z + extendZ,
                                    material, blockMass)) {
                                break growZ;
                            }
                        }
                        extendZ++;
                    }

                    int extendY = 1;
                    growY:
                    while (y + extendY < bounds.maxY()) {
                        for (int dz = 0; dz < extendZ; dz++) {
                            for (int dx = 0; dx < extendX; dx++) {
                                if (!matches(volume, visited, bounds, options, x + dx, y + extendY, z + dz,
                                        material, blockMass)) {
                                    break growY;
                                }
                            }
                        }
                        extendY++;
                    }

                    for (int dy = 0; dy < extendY; dy++) {
                        for (int dz = 0; dz < extendZ; dz++) {
                            for (int dx = 0; dx < extendX; dx++) {
                                visited[index(bounds, x + dx, y + dy, z + dz)] = true;
                            }
                        }
                    }

                    boxes.add(new VoxelBox(x, y, z, extendX, extendY, extendZ, material, blockMass));
                }
            }
        }

        return summarise(boxes, options.blockScale());
    }

    private static boolean matches(VoxelVolume volume, boolean[] visited, IntBounds3 bounds,
                                   VoxelColliderOptions options,
                                   int x, int y, int z,
                                   PhysicsMaterial material, double blockMass) {
        if (!volume.isSolid(x, y, z)) {
            return false;
        }
        if (visited[index(bounds, x, y, z)]) {
            return false;
        }
        if (options.mergeDifferentMaterials()) {
            return true;
        }
        return Double.compare(blockMass, volume.blockMass(x, y, z)) == 0
                && material.equals(volume.blockMaterial(x, y, z));
    }

    private static int index(IntBounds3 bounds, int x, int y, int z) {
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }

    private static VoxelColliderData summarise(List<VoxelBox> boxes, double scale) {
        if (boxes.isEmpty()) {
            return new VoxelColliderData(List.of(), scale, 0.0,
                    new Vector3d(), new Matrix3d().zero(), new Vector3d());
        }

        double totalMass = 0.0;
        final Vector3d weightedCenter = new Vector3d();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (VoxelBox box : boxes) {
            final double mass = box.mass();
            totalMass += mass;
            weightedCenter.add(new Vector3d(box.center()).mul(mass));
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.minX() + box.sizeX());
            maxY = Math.max(maxY, box.minY() + box.sizeY());
            maxZ = Math.max(maxZ, box.minZ() + box.sizeZ());
        }

        final Vector3d centerOfMass = weightedCenter.div(totalMass);
        final Matrix3d inertia = new Matrix3d().zero();

        for (VoxelBox box : boxes) {
            final double mass = box.mass();
            final Vector3d r = box.center().sub(centerOfMass).mul(scale);
            final double sx = box.sizeX() * scale;
            final double sy = box.sizeY() * scale;
            final double sz = box.sizeZ() * scale;

            final double ixx = mass / 12.0 * (sy * sy + sz * sz);
            final double iyy = mass / 12.0 * (sx * sx + sz * sz);
            final double izz = mass / 12.0 * (sx * sx + sy * sy);

            inertia.m00 += ixx + mass * (r.y * r.y + r.z * r.z);
            inertia.m11 += iyy + mass * (r.x * r.x + r.z * r.z);
            inertia.m22 += izz + mass * (r.x * r.x + r.y * r.y);
            final double ixy = -mass * r.x * r.y;
            final double ixz = -mass * r.x * r.z;
            final double iyz = -mass * r.y * r.z;
            inertia.m01 += ixy;
            inertia.m10 += ixy;
            inertia.m02 += ixz;
            inertia.m20 += ixz;
            inertia.m12 += iyz;
            inertia.m21 += iyz;
        }

        final Vector3d size = new Vector3d((maxX - minX) * scale, (maxY - minY) * scale, (maxZ - minZ) * scale);
        return new VoxelColliderData(boxes, scale, totalMass, centerOfMass, inertia, size);
    }
}
