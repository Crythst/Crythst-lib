package io.mikaple.crythst.integration.minecraft.structure.split;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureSplitterTest {
    private static final double BLOCK_MASS = 1000.0;

    @Test
    void fullCubeIsOneComponent() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 3, 3);
        final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(1, components.size());
        final StructureSplitter.Component component = components.get(0);
        assertEquals(27, component.cellCount());
        assertEquals(bounds, component.bounds());
        assertEquals(1, component.boxes().size());
        assertEquals(27, component.boxes().get(0).cellCount());
    }

    @Test
    void twoSeparatedIslandsBecomeTwoComponentsInDeterministicOrder() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 4, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(2, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(2, components.size());
        assertEquals(new StructureSplitter.Cell(0, 0, 0), components.get(0).cells().get(0));
        assertEquals(new StructureSplitter.Cell(2, 0, 0), components.get(1).cells().get(0));
        assertEquals(new IntBounds3(0, 0, 0, 1, 1, 1), components.get(0).bounds());
        assertEquals(new IntBounds3(2, 0, 0, 3, 1, 1), components.get(1).bounds());
    }

    @Test
    void diagonalContactIsNotConnected() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 1, 2);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 1, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(2, components.size());
        assertEquals(1, components.get(0).cellCount());
        assertEquals(1, components.get(1).cellCount());
    }

    @Test
    void connectedLShapeIsOneComponentWithMultipleBoxes() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(0, 1, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(1, components.size());
        final StructureSplitter.Component component = components.get(0);
        assertEquals(3, component.cellCount());
        assertEquals(bounds, component.bounds());
        assertEquals(2, component.boxes().size(), "greedy merge keeps the L as two boxes");
        assertEquals(3, component.boxes().stream().mapToInt(VoxelBox::cellCount).sum());
    }

    @Test
    void airCellsAreIgnored() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 3, 3);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);

        assertTrue(StructureSplitter.findComponents(volume).isEmpty());
    }

    @Test
    void emptyVolumeAndZeroMassVolumeAreMassless() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 2);
        assertTrue(StructureSplitter.isMassless(VoxelVolumes.empty(bounds)));
        assertTrue(StructureSplitter.isMassless(zeroMassVolume(bounds)));
        assertFalse(StructureSplitter.isMassless(
                VoxelVolumes.filled(bounds, BLOCK_MASS, PhysicsMaterial.STONE)));
    }

    @Test
    void negativeAndOffsetBoundsKeepNeighbourConnectivity() {
        final IntBounds3 bounds = new IntBounds3(-2, 3, -4, 2, 6, -2);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(-2, 3, -4, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(-2, 4, -4, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 5, -3, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(2, components.size());
        assertEquals(2, components.get(0).cellCount());
        assertEquals(new StructureSplitter.Cell(-2, 3, -4), components.get(0).cells().get(0));
        assertEquals(new StructureSplitter.Cell(-2, 4, -4), components.get(0).cells().get(1));
        assertEquals(new StructureSplitter.Cell(1, 5, -3), components.get(1).cells().get(0));
    }

    @Test
    void oversizedVolumeIsRejectedBeforeScanning() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 5000, 5000, 5000);
        final VoxelVolume huge = new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return false;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> StructureSplitter.findComponents(huge));
    }

    private static VoxelVolume zeroMassVolume(IntBounds3 bounds) {
        return new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return bounds.contains(x, y, z);
            }

            @Override
            public double blockMass(int x, int y, int z) {
                return 0.0;
            }
        };
    }
}
