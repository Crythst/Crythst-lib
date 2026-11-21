package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.util.Validate;

import java.util.function.Predicate;

/**
 * Helpers for building simple voxel volumes.
 */
public final class VoxelVolumes {
    private VoxelVolumes() {
    }

    /**
     * Creates a volume backed by a predicate. Solid cells use the default mass
     * and material.
     */
    public static VoxelVolume solid(IntBounds3 bounds, Predicate<Cell> predicate) {
        Validate.notNull(bounds, "bounds");
        Validate.notNull(predicate, "predicate");
        return new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return predicate.test(new Cell(x, y, z));
            }
        };
    }

    /** Creates a mutable volume where every cell starts empty. */
    public static MutableVoxelVolume empty(IntBounds3 bounds) {
        return new MutableVoxelVolume(bounds);
    }

    /** Creates a mutable volume filled with one solid block type. */
    public static MutableVoxelVolume filled(IntBounds3 bounds, double blockMass, PhysicsMaterial material) {
        final MutableVoxelVolume volume = new MutableVoxelVolume(bounds);
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    volume.setBlock(x, y, z, true, blockMass, material);
                }
            }
        }
        return volume;
    }

    public record Cell(int x, int y, int z) {
    }
}
