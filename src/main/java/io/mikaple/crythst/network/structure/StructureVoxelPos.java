package io.mikaple.crythst.network.structure;

/**
 * A structure-local voxel coordinate.
 *
 * <p>{@code (0, 0, 0)} is the structure's bounds minimum. The same coordinate
 * space is used by the voxel cell array and by local block-entity keys.</p>
 */
public record StructureVoxelPos(int x, int y, int z) {
    public StructureVoxelPos {
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException("Structure-local voxel must be non-negative: "
                    + x + "," + y + "," + z);
        }
    }

    /**
     * Packed key for client-side maps. Each axis gets 21 bits, which covers the
     * per-structure voxel cap ({@link StructureSyncLimits#MAX_CELLS}). Keep this
     * in sync with {@link #fromPacked(long)}.
     */
    public long packed() {
        return ((long) (x & 0x1F_FFFF) << 42)
                | ((long) (y & 0x1F_FFFF) << 21)
                | (z & 0x1F_FFFF);
    }

    public static StructureVoxelPos fromPacked(long packed) {
        return new StructureVoxelPos(
                (int) ((packed >>> 42) & 0x1F_FFFF),
                (int) ((packed >>> 21) & 0x1F_FFFF),
                (int) (packed & 0x1F_FFFF));
    }
}
