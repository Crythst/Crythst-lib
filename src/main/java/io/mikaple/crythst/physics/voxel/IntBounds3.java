package io.mikaple.crythst.physics.voxel;

/**
 * Integer voxel bounds: inclusive minimum, exclusive maximum.
 */
public record IntBounds3(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public IntBounds3 {
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            throw new IllegalArgumentException(
                    "Invalid voxel bounds: " + minX + "," + minY + "," + minZ + " -> "
                            + maxX + "," + maxY + "," + maxZ);
        }
    }

    public int sizeX() {
        return maxX - minX;
    }

    public int sizeY() {
        return maxY - minY;
    }

    public int sizeZ() {
        return maxZ - minZ;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < maxX
                && y >= minY && y < maxY
                && z >= minZ && z < maxZ;
    }

    public IntBounds3 expand(int amount) {
        return new IntBounds3(minX - amount, minY - amount, minZ - amount,
                maxX + amount, maxY + amount, maxZ + amount);
    }

    public IntBounds3 union(IntBounds3 other) {
        return new IntBounds3(
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ)
        );
    }
}
