package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.voxel.VoxelColliderHooks;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.util.Validate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Solid mask for one terrain section that keeps the exact Minecraft collision
 * shape of non-full blocks.
 *
 * <p>Full cubes take the cheap merged-box path. Slabs, stairs, fences and other
 * partial shapes store their actual {@code VoxelShape} boxes in voxel
 * coordinates, so a physics body rests on the visible surface instead of being
 * stopped by an invisible full cube (or clipping into the visual geometry).</p>
 *
 * <p>The class is both the {@link VoxelVolume} and the
 * {@link VoxelColliderHooks} passed to {@link
 * io.mikaple.crythst.physics.voxel.VoxelColliderBuilder}; the builder calls
 * {@link #collisionParts(int, int, int, VoxelVolume)} for every solid cell,
 * which is a hash-map lookup for the (normally rare) partial cells.</p>
 */
public final class TerrainSectionVolume implements VoxelVolume, VoxelColliderHooks {
    /** Shapes are quantised to 1/16 of a block; reject only numerical dust. */
    private static final double EPSILON = 1.0e-6;

    private final IntBounds3 bounds;
    private final boolean[] solid;
    private final Map<Integer, List<VoxelCellPart>> parts = new HashMap<>();
    private int solidCount;

    public TerrainSectionVolume(IntBounds3 bounds) {
        this.bounds = Validate.notNull(bounds, "bounds");
        this.solid = new boolean[Math.toIntExact(bounds.volume())];
    }

    /** Marks a cell solid with a full one-voxel cube. */
    public void setFullCube(int x, int y, int z) {
        setSolid(x, y, z, null);
    }

    /**
     * Marks a cell solid with precise geometry.
     *
     * @param cellParts parts in absolute voxel coordinates, or {@code null}
     *                  for a full cube
     */
    public void setSolid(int x, int y, int z, List<VoxelCellPart> cellParts) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        final int index = TerrainSectionMask.index(bounds, x, y, z);
        if (!solid[index]) {
            solid[index] = true;
            solidCount++;
        }
        if (cellParts == null || cellParts.isEmpty()) {
            parts.remove(index);
        } else {
            parts.put(index, List.copyOf(cellParts));
        }
    }

    public int solidCount() {
        return solidCount;
    }

    public boolean isEmpty() {
        return solidCount == 0;
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return false;
        }
        return solid[TerrainSectionMask.index(bounds, x, y, z)];
    }

    @Override
    public List<VoxelCellPart> collisionParts(int x, int y, int z, VoxelVolume volume) {
        if (!bounds.contains(x, y, z)) {
            return null;
        }
        return parts.get(TerrainSectionMask.index(bounds, x, y, z));
    }

    /**
     * Converts a Minecraft block collision shape into absolute voxel-space
     * parts. Coordinates are clamped to the owning cell: a shape that extends
     * outside its block would otherwise create geometry in a neighbour that the
     * section invalidation cache does not track.
     *
     * @return a non-empty list of parts, or an empty list when the shape has no
     *         usable volume
     */
    public static List<VoxelCellPart> partsFromShape(VoxelShape shape, int x, int y, int z) {
        if (shape == null || shape.isEmpty()) {
            return List.of();
        }
        final List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) {
            return List.of();
        }
        final List<VoxelCellPart> result = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            final double minX = clamp01(box.minX);
            final double minY = clamp01(box.minY);
            final double minZ = clamp01(box.minZ);
            final double maxX = clamp01(box.maxX);
            final double maxY = clamp01(box.maxY);
            final double maxZ = clamp01(box.maxZ);
            final double sizeX = maxX - minX;
            final double sizeY = maxY - minY;
            final double sizeZ = maxZ - minZ;
            if (sizeX <= EPSILON || sizeY <= EPSILON || sizeZ <= EPSILON) {
                continue;
            }
            result.add(new VoxelCellPart(
                    x + minX, y + minY, z + minZ,
                    sizeX, sizeY, sizeZ));
        }
        return result;
    }

    /**
     * True when the parts describe the complete one-voxel cube and can use the
     * merged full-cube path.
     */
    public static boolean isFullCube(List<VoxelCellPart> cellParts, int x, int y, int z) {
        if (cellParts == null || cellParts.size() != 1) {
            return false;
        }
        final VoxelCellPart part = cellParts.get(0);
        return Math.abs(part.minX() - x) <= EPSILON
                && Math.abs(part.minY() - y) <= EPSILON
                && Math.abs(part.minZ() - z) <= EPSILON
                && Math.abs(part.sizeX() - 1.0) <= EPSILON
                && Math.abs(part.sizeY() - 1.0) <= EPSILON
                && Math.abs(part.sizeZ() - 1.0) <= EPSILON;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
