package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a solid voxel grid into merged boxes plus exact mass properties.
 *
 * <p>The merge is a three-axis greedy sweep: grow along X, then Z, then Y. It
 * produces far fewer Box2d-style children than one box per block while keeping
 * the collision surface identical for axis-aligned blocks.</p>
 *
 * <p>An optional {@link VoxelColliderHooks} set can override the mass, material,
 * centre of mass or collision geometry of individual cells. Cells with a custom
 * centre of mass or custom geometry are never merged, so the override stays
 * exact; all other cells keep the normal greedy merge.</p>
 */
public final class VoxelColliderBuilder {
    private VoxelColliderBuilder() {
    }

    public static VoxelColliderData build(VoxelVolume volume) {
        return build(volume, VoxelColliderOptions.DEFAULT, VoxelColliderHooks.NONE);
    }

    public static VoxelColliderData build(VoxelVolume volume, VoxelColliderOptions options) {
        return build(volume, options, VoxelColliderHooks.NONE);
    }

    /**
     * Builds collision and mass data with per-cell overrides.
     *
     * @param hooks overrides consulted for every solid cell; use
     *              {@link VoxelColliderHooks#NONE} for the plain voxel shape
     */
    public static VoxelColliderData build(VoxelVolume volume, VoxelColliderOptions options,
                                          VoxelColliderHooks hooks) {
        final IntBounds3 bounds = volume.bounds();
        final long cellCount = bounds.volume();
        if (cellCount > options.maxCells()) {
            throw new IllegalArgumentException("Voxel volume is too large to colliderise: "
                    + cellCount + " cells (limit " + options.maxCells() + ")");
        }

        final int sizeX = bounds.sizeX();
        final int sizeZ = bounds.sizeZ();
        final boolean[] visited = new boolean[(int) cellCount];
        final List<CollisionBox> boxes = new ArrayList<>();
        final List<CustomCell> customCells = new ArrayList<>();

        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final int index = index(bounds, x, y, z);
                    if (visited[index] || !volume.isSolid(x, y, z)) {
                        continue;
                    }

                    final PhysicsMaterial material = material(volume, hooks, x, y, z);
                    final double blockMass = mass(volume, hooks, x, y, z);
                    final List<VoxelCellPart> parts = hooks.collisionParts(x, y, z, volume);
                    final Vector3dc centerOfMass = hooks.centerOfMass(x, y, z, volume);

                    if (parts != null) {
                        // Fractional geometry cannot be represented by the
                        // integer greedy box, so consume only this cell.
                        visited[index] = true;
                        customCells.add(new CustomCell(x, y, z, blockMass, parts, centerOfMass));
                        continue;
                    }

                    if (centerOfMass != null) {
                        // Keep the normal cube for collision and legacy
                        // box-list consumers, but use the hook's mass centre.
                        visited[index] = true;
                        final VoxelBox box = new VoxelBox(x, y, z, 1, 1, 1, material, blockMass);
                        boxes.add(new CollisionBox(box,
                                new Vector3d(x + centerOfMass.x(), y + centerOfMass.y(),
                                        z + centerOfMass.z())));
                        continue;
                    }

                    if (!options.greedyMerge()) {
                        visited[index] = true;
                        final VoxelBox box = new VoxelBox(x, y, z, 1, 1, 1, material, blockMass);
                        boxes.add(new CollisionBox(box, box.center()));
                        continue;
                    }

                    int extendX = 1;
                    while (x + extendX < bounds.maxX()
                            && matches(volume, visited, bounds, options, hooks,
                            x + extendX, y, z, material, blockMass)) {
                        extendX++;
                    }

                    int extendZ = 1;
                    growZ:
                    while (z + extendZ < bounds.maxZ()) {
                        for (int dx = 0; dx < extendX; dx++) {
                            if (!matches(volume, visited, bounds, options, hooks,
                                    x + dx, y, z + extendZ, material, blockMass)) {
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
                                if (!matches(volume, visited, bounds, options, hooks,
                                        x + dx, y + extendY, z + dz, material, blockMass)) {
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

                    final VoxelBox merged = new VoxelBox(x, y, z, extendX, extendY, extendZ,
                            material, blockMass);
                    boxes.add(new CollisionBox(merged, merged.center()));
                }
            }
        }

        return summarise(boxes, customCells, options.blockScale());
    }

    private static PhysicsMaterial material(VoxelVolume volume, VoxelColliderHooks hooks,
                                            int x, int y, int z) {
        final PhysicsMaterial override = hooks.material(x, y, z, volume);
        return override == null ? volume.blockMaterial(x, y, z) : override;
    }

    private static double mass(VoxelVolume volume, VoxelColliderHooks hooks, int x, int y, int z) {
        final Double override = hooks.mass(x, y, z, volume);
        final double value = override == null ? volume.blockMass(x, y, z) : override;
        return Math.max(value, 0.0);
    }

    private static boolean matches(VoxelVolume volume, boolean[] visited, IntBounds3 bounds,
                                   VoxelColliderOptions options, VoxelColliderHooks hooks,
                                   int x, int y, int z,
                                   PhysicsMaterial material, double blockMass) {
        if (!volume.isSolid(x, y, z)) {
            return false;
        }
        if (visited[index(bounds, x, y, z)]) {
            return false;
        }
        // A neighbour with its own shape/COM override must stay independent.
        if (hooks.collisionParts(x, y, z, volume) != null
                || hooks.centerOfMass(x, y, z, volume) != null) {
            return false;
        }
        if (options.mergeDifferentMaterials()) {
            return true;
        }
        return Double.compare(blockMass, mass(volume, hooks, x, y, z)) == 0
                && material.equals(material(volume, hooks, x, y, z));
    }

    private static int index(IntBounds3 bounds, int x, int y, int z) {
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }

    private static VoxelColliderData summarise(List<CollisionBox> boxes, List<CustomCell> customCells,
                                               double scale) {
        if (boxes.isEmpty() && customCells.isEmpty()) {
            return new VoxelColliderData(List.of(), List.of(), scale, 0.0,
                    new Vector3d(), new Matrix3d().zero(), new Vector3d());
        }

        double totalMass = 0.0;
        final Vector3d weightedCenter = new Vector3d();
        for (CollisionBox entry : boxes) {
            final double mass = entry.box.mass();
            totalMass += mass;
            weightedCenter.add(new Vector3d(entry.massCenter).mul(mass));
        }
        for (CustomCell cell : customCells) {
            totalMass += cell.mass;
            weightedCenter.add(new Vector3d(cell.massCenter()).mul(cell.mass));
        }

        final Vector3d centerOfMass = totalMass > 0.0
                ? weightedCenter.div(totalMass)
                : new Vector3d();
        final Matrix3d inertia = new Matrix3d().zero();
        final List<VoxelCellPart> customParts = new ArrayList<>();

        for (CollisionBox entry : boxes) {
            final VoxelBox box = entry.box;
            addBoxInertia(inertia, box.mass(),
                    entry.massCenter, box.sizeX(), box.sizeY(), box.sizeZ(),
                    centerOfMass, scale);
        }
        for (CustomCell cell : customCells) {
            if (cell.parts.isEmpty()) {
                continue;
            }
            customParts.addAll(cell.parts);
            double partVolume = 0.0;
            for (VoxelCellPart part : cell.parts) {
                partVolume += part.volume();
            }
            for (VoxelCellPart part : cell.parts) {
                final double partMass = partVolume > 0.0
                        ? cell.mass * part.volume() / partVolume
                        : cell.mass / cell.parts.size();
                addBoxInertia(inertia, partMass, part.center(),
                        part.sizeX(), part.sizeY(), part.sizeZ(),
                        centerOfMass, scale);
            }
        }

        final List<VoxelBox> plainBoxes = new ArrayList<>(boxes.size());
        for (CollisionBox entry : boxes) {
            plainBoxes.add(entry.box);
        }
        final Vector3d size = boundsSize(boxes, customParts, scale);
        return new VoxelColliderData(plainBoxes, customParts, scale, totalMass,
                centerOfMass, inertia, size);
    }

    private static void addBoxInertia(Matrix3d inertia, double mass, Vector3dc center,
                                      double sizeX, double sizeY, double sizeZ,
                                      Vector3dc centerOfMass, double scale) {
        final Vector3d r = new Vector3d(center).sub(centerOfMass).mul(scale);
        final double sx = sizeX * scale;
        final double sy = sizeY * scale;
        final double sz = sizeZ * scale;

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

    private static Vector3d boundsSize(List<CollisionBox> boxes, List<VoxelCellPart> parts,
                                       double scale) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (CollisionBox entry : boxes) {
            final VoxelBox box = entry.box;
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.minX() + box.sizeX());
            maxY = Math.max(maxY, box.minY() + box.sizeY());
            maxZ = Math.max(maxZ, box.minZ() + box.sizeZ());
        }
        for (VoxelCellPart part : parts) {
            minX = Math.min(minX, part.minX());
            minY = Math.min(minY, part.minY());
            minZ = Math.min(minZ, part.minZ());
            maxX = Math.max(maxX, part.minX() + part.sizeX());
            maxY = Math.max(maxY, part.minY() + part.sizeY());
            maxZ = Math.max(maxZ, part.minZ() + part.sizeZ());
        }
        if (!Double.isFinite(minX)) {
            return new Vector3d();
        }
        return new Vector3d((maxX - minX) * scale, (maxY - minY) * scale, (maxZ - minZ) * scale);
    }

    private record CollisionBox(VoxelBox box, Vector3d massCenter) {
    }

    private record CustomCell(int x, int y, int z, double mass,
                              List<VoxelCellPart> parts, Vector3dc centerOfMass) {
        Vector3d massCenter() {
            if (centerOfMass != null) {
                return new Vector3d(x + centerOfMass.x(), y + centerOfMass.y(), z + centerOfMass.z());
            }
            if (parts.isEmpty()) {
                return new Vector3d(x + 0.5, y + 0.5, z + 0.5);
            }
            double volume = 0.0;
            final Vector3d weighted = new Vector3d();
            for (VoxelCellPart part : parts) {
                final double partVolume = part.volume();
                volume += partVolume;
                weighted.add(new Vector3d(part.center()).mul(partVolume));
            }
            return volume > 0.0 ? weighted.div(volume) : new Vector3d(x + 0.5, y + 0.5, z + 0.5);
        }
    }
}
