package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;

/**
 * Small dependency-free volumes used by the persistence tests. They never touch
 * a Minecraft level or registry.
 */
final class TestVolumes {
    private TestVolumes() {
    }

    static final IntBounds3 BOUNDS = new IntBounds3(0, 0, 0, 3, 2, 2);

    /**
     * An asymmetric L-ish shape with non-uniform mass and two materials, so a
     * snapshot that loses per-cell data shows up in the centre of mass or the
     * round-trip assertions.
     */
    static VoxelVolume asymmetric() {
        return new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return BOUNDS;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return (x == 0 && y == 0 && z == 0)
                        || (x == 1 && y == 0 && z == 0)
                        || (x == 0 && y == 0 && z == 1)
                        || (x == 1 && y == 1 && z == 0)
                        || (x == 2 && y == 0 && z == 1);
            }

            @Override
            public double blockMass(int x, int y, int z) {
                return isSolid(x, y, z) ? 500.0 + 70.0 * x + 110.0 * y + 130.0 * z : 0.0;
            }

            @Override
            public PhysicsMaterial blockMaterial(int x, int y, int z) {
                return isSolid(x, y, z) && (x + y + z) % 2 == 0
                        ? PhysicsMaterial.STONE : PhysicsMaterial.WOOD;
            }
        };
    }
}
