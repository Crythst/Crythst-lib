package io.mikaple.crythst.client.render.light;

import java.util.Arrays;

/**
 * Immutable solid-occupancy mask for one structure body.
 *
 * <p>The mask covers the structure's voxel bounds. A cell is marked solid when
 * the corresponding {@code BlockState} can occlude light
 * ({@code BlockState.canOcclude()}). It is deliberately independent of
 * Minecraft's light engine: structures move and are rendered outside the chunk
 * pipeline, so this is the small, queryable representation that face culling,
 * vertex AO and future entity shading can share.</p>
 *
 * <p>Coordinates passed to {@link #isSolid(int, int, int)} are absolute voxel
 * coordinates in the same space as the structure snapshot, i.e. the structure's
 * block grid before the render pose and block scale are applied. Cells outside
 * the mask are treated as air.</p>
 */
public final class StructureLightOccluder {
    public static final StructureLightOccluder EMPTY =
            new StructureLightOccluder(0, 0, 0, 0, 0, 0, new long[0], 0);

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final long[] words;
    private final int solidCount;

    private StructureLightOccluder(int minX, int minY, int minZ,
                                   int sizeX, int sizeY, int sizeZ,
                                   long[] words, int solidCount) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.words = words;
        this.solidCount = solidCount;
    }

    public static Builder builder(int minX, int minY, int minZ,
                                  int sizeX, int sizeY, int sizeZ) {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new IllegalArgumentException("Structure bounds must be non-negative");
        }
        final long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Structure volume too large: " + volume);
        }
        return new Builder(minX, minY, minZ, sizeX, sizeY, sizeZ,
                new long[(int) ((volume + 63L) >>> 6)]);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    /** Number of cells marked solid. */
    public int solidCount() {
        return solidCount;
    }

    public boolean isEmpty() {
        return solidCount == 0;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && y >= minY && z >= minZ
                && x < minX + sizeX && y < minY + sizeY && z < minZ + sizeZ;
    }

    /**
     * Returns whether the voxel is a solid occluder. Out-of-bounds cells are air.
     */
    public boolean isSolid(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return false;
        }
        final long index = ((long) (y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
        return (words[(int) (index >>> 6)] & (1L << (index & 63))) != 0L;
    }

    /** Builder used while a body's section states are copied. */
    public static final class Builder {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final long[] words;
        private int solidCount;

        private Builder(int minX, int minY, int minZ,
                        int sizeX, int sizeY, int sizeZ, long[] words) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.words = words;
        }

        public void setSolid(int x, int y, int z, boolean solid) {
            if (!solid || x < minX || y < minY || z < minZ
                    || x >= minX + sizeX || y >= minY + sizeY || z >= minZ + sizeZ) {
                return;
            }
            final long index = ((long) (y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
            final int word = (int) (index >>> 6);
            final long mask = 1L << (index & 63);
            if ((words[word] & mask) == 0L) {
                words[word] |= mask;
                solidCount++;
            }
        }

        public StructureLightOccluder build() {
            if (solidCount == 0) {
                return EMPTY;
            }
            // Copy so later builder writes cannot mutate a published mask.
            return new StructureLightOccluder(minX, minY, minZ,
                    sizeX, sizeY, sizeZ, Arrays.copyOf(words, words.length), solidCount);
        }
    }
}
