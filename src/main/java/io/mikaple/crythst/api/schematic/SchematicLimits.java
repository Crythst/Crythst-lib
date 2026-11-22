package io.mikaple.crythst.api.schematic;

import io.mikaple.crythst.physics.voxel.IntBounds3;

import java.util.Objects;

/**
 * Resource limits applied while decoding a schematic.
 *
 * <p>Vanilla structure NBT stores the size in the file, so the size is checked
 * <em>before</em> the dense cell arrays are allocated. The block count and NBT
 * quota are checked during decoding. Defaults deliberately match the assembly
 * limits used elsewhere in Crythst: at most 1,000,000 non-air blocks and
 * 4,000,000 visited cells, with a 64 MiB compressed-NBT quota.</p>
 *
 * @param maxSizeX        maximum extent on the X axis, inclusive
 * @param maxSizeY        maximum extent on the Y axis, inclusive
 * @param maxSizeZ        maximum extent on the Z axis, inclusive
 * @param maxCells        maximum number of cells in the dense volume
 * @param maxNonAirBlocks maximum number of non-air blocks accepted
 * @param maxNbtBytes     maximum NBT heap for compressed file loading
 */
public record SchematicLimits(
        int maxSizeX,
        int maxSizeY,
        int maxSizeZ,
        long maxCells,
        int maxNonAirBlocks,
        long maxNbtBytes
) {
    /** Defaults suitable for interactive use and structure persistence. */
    public static final SchematicLimits DEFAULT =
            new SchematicLimits(1024, 1024, 1024, 4_000_000L, 1_000_000, 64L << 20);

    public SchematicLimits {
        if (maxSizeX <= 0 || maxSizeY <= 0 || maxSizeZ <= 0) {
            throw new IllegalArgumentException("Schematic axis limits must be positive");
        }
        if (maxCells <= 0L) {
            throw new IllegalArgumentException("maxCells must be positive");
        }
        if (maxNonAirBlocks <= 0) {
            throw new IllegalArgumentException("maxNonAirBlocks must be positive");
        }
        if (maxNbtBytes <= 0L) {
            throw new IllegalArgumentException("maxNbtBytes must be positive");
        }
    }

    public static SchematicLimits defaults() {
        return DEFAULT;
    }

    /**
     * Validates the axis extents and dense cell count before allocation.
     *
     * @param bounds decoded bounds
     * @throws SchematicException when an axis or the total cell count exceeds
     *                            the limits
     */
    public void validateBounds(IntBounds3 bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.sizeX() > maxSizeX || bounds.sizeY() > maxSizeY
                || bounds.sizeZ() > maxSizeZ) {
            throw new SchematicException("Schematic size " + bounds.sizeX() + "x"
                    + bounds.sizeY() + "x" + bounds.sizeZ() + " exceeds the limit "
                    + maxSizeX + "x" + maxSizeY + "x" + maxSizeZ);
        }
        if (bounds.volume() > maxCells) {
            throw new SchematicException("Schematic volume " + bounds.volume()
                    + " cells exceeds the limit " + maxCells);
        }
    }

    /**
     * Validates the block count after the cell array has been populated.
     *
     * @param nonAirBlocks number of non-air blocks
     * @throws SchematicException when the block count exceeds the limit
     */
    public void validateBlockCount(long nonAirBlocks) {
        if (nonAirBlocks > maxNonAirBlocks) {
            throw new SchematicException("Schematic contains " + nonAirBlocks
                    + " blocks, exceeding the limit " + maxNonAirBlocks);
        }
    }
}
