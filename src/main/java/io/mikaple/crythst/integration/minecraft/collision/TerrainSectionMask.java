package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.util.Validate;

/**
 * A dense solid/empty mask over one 16x16x16 section.
 *
 * <p>Minecraft block states are read exactly once per cell when the mask is
 * filled. {@link io.mikaple.crythst.physics.voxel.VoxelColliderBuilder} then
 * queries the mask repeatedly during its greedy merge; those queries are plain
 * array reads, so a section build performs no per-cell allocation and no
 * repeated world lookups.</p>
 *
 * <p>Terrain uses the default mass and material: static bodies have infinite
 * mass and all merged boxes simply share identical properties.</p>
 */
public final class TerrainSectionMask implements VoxelVolume {
    private final IntBounds3 bounds;
    private final boolean[] solid;
    private int solidCount;

    public TerrainSectionMask(IntBounds3 bounds) {
        this.bounds = Validate.notNull(bounds, "bounds");
        this.solid = new boolean[Math.toIntExact(bounds.volume())];
    }

    /**
     * Marks a cell solid. Positions outside the mask are ignored so callers
     * can safely iterate a slightly larger neighbourhood.
     */
    public void setSolid(int x, int y, int z, boolean value) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        final int index = index(bounds, x, y, z);
        if (solid[index] != value) {
            solid[index] = value;
            solidCount += value ? 1 : -1;
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
        return solid[index(bounds, x, y, z)];
    }

    /**
     * Shared indexing convention with the voxel builder: X is contiguous,
     * then Z, then Y.
     */
    public static int index(IntBounds3 bounds, int x, int y, int z) {
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }
}
