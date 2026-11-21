package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSectionGeometryTest {
    @Test
    void sectionCoordinatesUseFloorSemanticsAtNegativeBoundaries() {
        assertEquals(new TerrainSectionGeometry.Key(0, 0, 0),
                TerrainSectionGeometry.Key.containing(0, 0, 0));
        assertEquals(new TerrainSectionGeometry.Key(0, 0, 0),
                TerrainSectionGeometry.Key.containing(15, 15, 15));
        assertEquals(new TerrainSectionGeometry.Key(1, 1, 1),
                TerrainSectionGeometry.Key.containing(16, 16, 16));
        assertEquals(new TerrainSectionGeometry.Key(-1, -1, -1),
                TerrainSectionGeometry.Key.containing(-1, -1, -1));
        assertEquals(new TerrainSectionGeometry.Key(-1, -1, -1),
                TerrainSectionGeometry.Key.containing(-16, -16, -16));
        assertEquals(new TerrainSectionGeometry.Key(-2, -2, -2),
                TerrainSectionGeometry.Key.containing(-17, -17, -17));

        assertEquals(-1, TerrainSectionGeometry.Key.containingFloor(-0.001, -0.001, -0.001).x());
        assertEquals(-1, TerrainSectionGeometry.Key.containingFloor(-16.0, -16.0, -16.0).x());
        assertEquals(-2, TerrainSectionGeometry.Key.containingFloor(-16.0001, -16.0001, -16.0001).x());
        assertEquals(0, TerrainSectionGeometry.Key.containingFloor(15.999, 15.999, 15.999).x());
        assertEquals(1, TerrainSectionGeometry.Key.containingFloor(16.0, 16.0, 16.0).x());
    }

    @Test
    void sectionBoundsAreAlignedAndHandleNegativeKeys() {
        final IntBounds3 positive = new TerrainSectionGeometry.Key(2, 3, 4).bounds();
        assertEquals(32, positive.minX());
        assertEquals(48, positive.maxX());
        assertEquals(48, positive.minY());
        assertEquals(64, positive.maxY());
        assertEquals(64, positive.minZ());
        assertEquals(80, positive.maxZ());

        final IntBounds3 negative = new TerrainSectionGeometry.Key(-1, -1, -1).bounds();
        assertEquals(-16, negative.minX());
        assertEquals(0, negative.maxX());
        assertEquals(-16, negative.minY());
        assertEquals(0, negative.maxY());
        assertEquals(-16, negative.minZ());
        assertEquals(0, negative.maxZ());
    }

    @Test
    void boundaryHelpersAndLocalCoordinatesUseFloorMod() {
        assertEquals(0, TerrainSectionGeometry.localCoordinate(-16));
        assertEquals(15, TerrainSectionGeometry.localCoordinate(-1));
        assertEquals(0, TerrainSectionGeometry.localCoordinate(0));
        assertEquals(15, TerrainSectionGeometry.localCoordinate(15));

        assertTrue(TerrainSectionGeometry.touchesNegativeBoundary(-16));
        assertTrue(TerrainSectionGeometry.touchesPositiveBoundary(-1));
        assertFalse(TerrainSectionGeometry.touchesNegativeBoundary(-15));
        assertFalse(TerrainSectionGeometry.touchesPositiveBoundary(-2));
    }

    @Test
    void maskMergesSingleNegativeSectionAndStoresAbsoluteCoordinates() {
        final IntBounds3 bounds = new TerrainSectionGeometry.Key(-1, 0, -1).bounds();
        final TerrainSectionMask mask = new TerrainSectionMask(bounds);
        mask.setSolid(-16, 0, -16, true);
        mask.setSolid(-15, 0, -16, true);
        mask.setSolid(0, 0, 0, true); // outside, must be ignored
        assertEquals(2, mask.solidCount());

        final VoxelColliderData data = VoxelColliderBuilder.build(mask,
                io.mikaple.crythst.physics.voxel.VoxelColliderOptions.DEFAULT.withMergedMaterials(true));
        final List<VoxelBox> boxes = data.boxes();
        assertEquals(1, boxes.size(), "two adjacent cells should merge into one box");
        final VoxelBox box = boxes.get(0);
        assertEquals(-16, box.minX());
        assertEquals(0, box.minY());
        assertEquals(-16, box.minZ());
        assertEquals(2, box.sizeX());

        assertEquals(-15.0, data.centerOfMass().x, 1.0e-9);
        assertEquals(0.5, data.centerOfMass().y, 1.0e-9);
        assertEquals(-15.5, data.centerOfMass().z, 1.0e-9);

        final Vector3d position = TerrainSectionGeometry.staticBodyPosition(bounds, data);
        assertEquals(-15.0, position.x, 1.0e-9);
        assertEquals(0.5, position.y, 1.0e-9);
        assertEquals(-15.5, position.z, 1.0e-9);

        // The compound shape is emitted relative to the centre of mass.
        assertEquals(0.0, data.toCompoundShape().children().get(0).transform().position().x, 1.0e-9);
    }

    @Test
    void filledBottomLayersOfNegativeSectionHaveExpectedCentreOfMass() {
        final IntBounds3 bounds = new TerrainSectionGeometry.Key(-1, 0, -1).bounds();
        final TerrainSectionMask mask = new TerrainSectionMask(bounds);
        for (int y = 0; y < 4; y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    mask.setSolid(x, y, z, true);
                }
            }
        }
        assertEquals(16 * 16 * 4, mask.solidCount());

        final VoxelColliderData data = VoxelColliderBuilder.build(mask,
                io.mikaple.crythst.physics.voxel.VoxelColliderOptions.DEFAULT.withMergedMaterials(true));
        assertEquals(1, data.boxes().size());
        assertEquals(-8.0, data.centerOfMass().x, 1.0e-9);
        assertEquals(2.0, data.centerOfMass().y, 1.0e-9);
        assertEquals(-8.0, data.centerOfMass().z, 1.0e-9);

        final Vector3d position = TerrainSectionGeometry.staticBodyPosition(bounds, data);
        assertEquals(-8.0, position.x, 1.0e-9);
        assertEquals(2.0, position.y, 1.0e-9);
        assertEquals(-8.0, position.z, 1.0e-9);
        assertEquals(16.0, data.size().x, 1.0e-9);
        assertEquals(4.0, data.size().y, 1.0e-9);
        assertEquals(16.0, data.size().z, 1.0e-9);
    }

    @Test
    void sectionMaskIndexMatchesVoxelBuilderLayout() {
        final IntBounds3 bounds = new IntBounds3(-3, -2, -1, 5, 4, 7);
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final int expected = ((y - bounds.minY()) * bounds.sizeZ() + (z - bounds.minZ()))
                            * bounds.sizeX() + (x - bounds.minX());
                    assertEquals(expected, TerrainSectionMask.index(bounds, x, y, z));
                }
            }
        }
    }
}
