package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import org.joml.Vector3d;

/**
 * Pure geometry helpers for Minecraft's 16x16x16 terrain sections.
 *
 * <p>This class deliberately has no Minecraft imports so it can be unit
 * tested on a plain JVM. Section coordinates follow Minecraft's convention:
 * section {@code (0,0,0)} covers blocks {@code [0,16)} and section
 * {@code (-1,0,0)} covers {@code [-16,0)}.</p>
 */
public final class TerrainSectionGeometry {
    public static final int SECTION_SIZE = 16;
    public static final int SECTION_CELLS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private TerrainSectionGeometry() {
    }

    /**
     * Integer section coordinates. {@code x} and {@code z} are also the
     * column's chunk coordinates because a section spans one full chunk
     * horizontally.
     */
    public record Key(int x, int y, int z) {
        public int originX() {
            return Math.multiplyExact(x, SECTION_SIZE);
        }

        public int originY() {
            return Math.multiplyExact(y, SECTION_SIZE);
        }

        public int originZ() {
            return Math.multiplyExact(z, SECTION_SIZE);
        }

        /**
         * The section as block-coordinate bounds (minimum inclusive, maximum
         * exclusive). This is the coordinate space the voxel collider builder
         * consumes.
         */
        public IntBounds3 bounds() {
            return new IntBounds3(originX(), originY(), originZ(),
                    originX() + SECTION_SIZE, originY() + SECTION_SIZE, originZ() + SECTION_SIZE);
        }

        /** Chunk column that contains this section. */
        public int chunkX() {
            return x;
        }

        /** Chunk column that contains this section. */
        public int chunkZ() {
            return z;
        }

        public Key offset(int dx, int dy, int dz) {
            return new Key(x + dx, y + dy, z + dz);
        }

        public boolean contains(int blockX, int blockY, int blockZ) {
            return Math.floorDiv(blockX, SECTION_SIZE) == x
                    && Math.floorDiv(blockY, SECTION_SIZE) == y
                    && Math.floorDiv(blockZ, SECTION_SIZE) == z;
        }

        /** Section containing an integer block position. */
        public static Key containing(int blockX, int blockY, int blockZ) {
            return new Key(Math.floorDiv(blockX, SECTION_SIZE),
                    Math.floorDiv(blockY, SECTION_SIZE),
                    Math.floorDiv(blockZ, SECTION_SIZE));
        }

        /** Section containing a world-space (double) position. */
        public static Key containingFloor(double x, double y, double z) {
            return new Key(sectionCoordinate(x), sectionCoordinate(y), sectionCoordinate(z));
        }
    }

    /**
     * Converts a double world coordinate into a section index using floor
     * semantics, so {@code -0.001} lands in section {@code -1} and
     * {@code -16.0} still lands in section {@code -1}.
     */
    public static int sectionCoordinate(double coordinate) {
        return (int) Math.floorDiv((long) Math.floor(coordinate), (long) SECTION_SIZE);
    }

    /**
     * World-space position for a static section body.
     *
     * <p>{@link VoxelColliderData}'s centre of mass is stored in unscaled
     * <em>voxel</em> coordinates. Terrain voxel coordinates are absolute
     * block coordinates, so the body position is the section origin plus the
     * centre of mass relative to that origin, scaled to metres. This matters
     * for negative section coordinates: simply adding the absolute centre of
     * mass to the section origin double-counts the offset.</p>
     */
    public static Vector3d staticBodyPosition(IntBounds3 sectionBounds, VoxelColliderData collider) {
        final Vector3d origin = new Vector3d(sectionBounds.minX(), sectionBounds.minY(), sectionBounds.minZ());
        final Vector3d localCenter = collider.centerOfMass().sub(origin);
        return origin.mul(collider.blockScale()).add(localCenter.mul(collider.blockScale()));
    }

    /** Local coordinate in {@code [0, 16)} for a block coordinate. */
    public static int localCoordinate(int blockCoordinate) {
        return Math.floorMod(blockCoordinate, SECTION_SIZE);
    }

    /** True when the block touches the negative-side section boundary. */
    public static boolean touchesNegativeBoundary(int blockCoordinate) {
        return localCoordinate(blockCoordinate) == 0;
    }

    /** True when the block touches the positive-side section boundary. */
    public static boolean touchesPositiveBoundary(int blockCoordinate) {
        return localCoordinate(blockCoordinate) == SECTION_SIZE - 1;
    }
}
