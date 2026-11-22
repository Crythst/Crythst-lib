package io.mikaple.crythst.integration.minecraft.structure.split;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A split component can be rejected by {@code PhysicsStructure} (no mass or no
 * collision geometry). The split used to fail, roll back and retry on every
 * tick, which made the structure permanently unsplittable. These tests pin the
 * pre-flight build and the merge that replaces that loop.
 */
class StructureSplitterWeightlessMergeTest {
    private static final double BLOCK_MASS = 1000.0;

    @Test
    void stoneBlobIsBuildable() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        assertTrue(StructureSplitter.isBuildable(volume, VoxelColliderOptions.DEFAULT));
    }

    @Test
    void volumeWithoutSolidCellsIsNotBuildable() {
        final MutableVoxelVolume volume = VoxelVolumes.empty(new IntBounds3(0, 0, 0, 3, 1, 1));

        // No mass and no geometry: exactly what PhysicsStructure rejects.
        assertFalse(StructureSplitter.isBuildable(volume, VoxelColliderOptions.DEFAULT));
    }

    @Test
    void componentVolumeOfASolidCellIsBuildable() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 4, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final StructureSplitter.Component component = StructureSplitter.findComponents(volume).get(0);
        final VoxelVolume built = StructureSplitter.componentVolume(volume, component);

        // copyComponent always writes the component's cells as solid, so a
        // component can only lose its geometry through the block hooks, which is
        // what isBuildable() is able to detect.
        assertTrue(StructureSplitter.isBuildable(built, VoxelColliderOptions.DEFAULT));
    }

    @Test
    void unbuildableComponentJoinsTheNearestBody() {
        final List<StructureSplitter.Component> components = List.of(
                component(new IntBounds3(0, 0, 0, 1, 1, 1), 0),
                component(new IntBounds3(8, 0, 0, 9, 1, 1), 8),
                component(new IntBounds3(6, 0, 0, 7, 1, 1), 6));

        final List<StructureSplitter.Component> merged =
                StructureSplitter.mergeUnbuildableComponents(components,
                        new boolean[]{true, true, false});

        assertEquals(2, merged.size(), "the unbuildable island must not stay a body of its own");
        assertEquals(1, merged.get(0).cellCount());
        assertEquals(2, merged.get(1).cellCount(), "its blocks must survive in the neighbour");
        assertTrue(merged.get(1).cells().contains(new StructureSplitter.Cell(6, 0, 0)));
        assertTrue(merged.get(1).cells().contains(new StructureSplitter.Cell(8, 0, 0)));
        assertEquals(new IntBounds3(6, 0, 0, 9, 1, 1), merged.get(1).bounds());
    }

    @Test
    void mergedCellsKeepTheDeterministicScanOrder() {
        final List<StructureSplitter.Component> components = List.of(
                component(new IntBounds3(4, 0, 0, 5, 1, 1), 4),
                component(new IntBounds3(0, 0, 0, 1, 1, 1), 0));

        final List<StructureSplitter.Component> merged =
                StructureSplitter.mergeUnbuildableComponents(components,
                        new boolean[]{true, false});

        assertEquals(1, merged.size());
        assertEquals(List.of(new StructureSplitter.Cell(0, 0, 0), new StructureSplitter.Cell(4, 0, 0)),
                merged.get(0).cells(), "Y, then Z, then X order like findComponents");
    }

    @Test
    void mergedComponentStaysBuildable() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 10, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(8, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> merged =
                StructureSplitter.mergeUnbuildableComponents(
                        StructureSplitter.findComponents(volume), new boolean[]{true, false});

        assertEquals(1, merged.size());
        final VoxelVolume built = StructureSplitter.componentVolume(volume, merged.get(0));
        assertTrue(StructureSplitter.isBuildable(built, VoxelColliderOptions.DEFAULT),
                "folding must leave a component PhysicsStructure still accepts");
    }

    @Test
    void allBuildableComponentsAreMergedWithoutLoss() {
        final List<StructureSplitter.Component> components = List.of(
                component(new IntBounds3(0, 0, 0, 1, 1, 1), 0),
                component(new IntBounds3(3, 0, 0, 4, 1, 1), 3));

        final List<StructureSplitter.Component> merged =
                StructureSplitter.mergeUnbuildableComponents(components,
                        new boolean[]{true, true});

        assertEquals(2, merged.size());
        final List<StructureSplitter.Cell> cells = new ArrayList<>();
        merged.forEach(component -> cells.addAll(component.cells()));
        assertEquals(2, cells.size());
        assertTrue(cells.contains(new StructureSplitter.Cell(0, 0, 0)));
        assertTrue(cells.contains(new StructureSplitter.Cell(3, 0, 0)));
    }

    private static StructureSplitter.Component component(IntBounds3 bounds, int x) {
        return new StructureSplitter.Component(bounds,
                List.of(new StructureSplitter.Cell(x, bounds.minY(), bounds.minZ())),
                List.of());
    }
}
