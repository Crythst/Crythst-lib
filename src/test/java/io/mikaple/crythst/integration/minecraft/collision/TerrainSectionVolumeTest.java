package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSectionVolumeTest {
    @Test
    void partialShapeKeepsExactVoxelGeometry() {
        // Bottom slab: collision covers the lower half of the cell only.
        final VoxelShape slab = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        final List<VoxelCellPart> parts = TerrainSectionVolume.partsFromShape(slab, 4, 70, -3);
        assertEquals(1, parts.size());
        final VoxelCellPart part = parts.get(0);
        assertEquals(4.0, part.minX(), 1.0e-9);
        assertEquals(70.0, part.minY(), 1.0e-9);
        assertEquals(-3.0, part.minZ(), 1.0e-9);
        assertEquals(1.0, part.sizeX(), 1.0e-9);
        assertEquals(0.5, part.sizeY(), 1.0e-9);
        assertEquals(1.0, part.sizeZ(), 1.0e-9);
        assertFalse(TerrainSectionVolume.isFullCube(parts, 4, 70, -3));
    }

    @Test
    void multipleShapeBoxesBecomeMultipleParts() {
        // Stair-like two-box shape.
        final VoxelShape shape = Shapes.or(
                Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                Shapes.box(0.5, 0.5, 0.0, 1.0, 1.0, 1.0));
        final List<VoxelCellPart> parts = TerrainSectionVolume.partsFromShape(shape, 0, 0, 0);
        assertEquals(2, parts.size());
        assertEquals(0.5, parts.get(0).sizeY(), 1.0e-9);
        assertEquals(0.5, parts.get(1).sizeX(), 1.0e-9);
    }

    @Test
    void fullCubeShapeUsesTheMergedPath() {
        final VoxelShape cube = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        final List<VoxelCellPart> parts = TerrainSectionVolume.partsFromShape(cube, 2, 3, 4);
        assertTrue(TerrainSectionVolume.isFullCube(parts, 2, 3, 4));
    }

    @Test
    void emptyShapeContributesNoParts() {
        assertTrue(TerrainSectionVolume.partsFromShape(Shapes.empty(), 0, 0, 0).isEmpty());
    }

    @Test
    void fullAndPartialCellsCoexistInOneMask() {
        final TerrainSectionVolume volume = new TerrainSectionVolume(new IntBounds3(0, 0, 0, 16, 16, 16));
        volume.setFullCube(1, 2, 3);
        final List<VoxelCellPart> parts = TerrainSectionVolume.partsFromShape(
                Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), 2, 2, 3);
        volume.setSolid(2, 2, 3, parts);

        assertEquals(2, volume.solidCount());
        assertTrue(volume.isSolid(1, 2, 3));
        assertTrue(volume.isSolid(2, 2, 3));
        assertFalse(volume.isSolid(3, 2, 3));
        assertNull(volume.collisionParts(1, 2, 3, volume), "full cubes must stay mergeable");
        assertEquals(parts, volume.collisionParts(2, 2, 3, volume));
    }
}
