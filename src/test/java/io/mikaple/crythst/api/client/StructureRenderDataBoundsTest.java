package io.mikaple.crythst.api.client;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureRenderDataBoundsTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void convertsVoxelBoundsToWorldBoundsAtTheStructureScale() {
        final Aabb bounds = StructureRenderData.worldBounds(
                new IntBounds3(0, 0, 0, 2, 1, 1),
                Transform3d.IDENTITY, 2.0);

        assertEquals(0.0, bounds.min().x, EPSILON);
        assertEquals(0.0, bounds.min().y, EPSILON);
        assertEquals(0.0, bounds.min().z, EPSILON);
        assertEquals(4.0, bounds.max().x, EPSILON);
        assertEquals(2.0, bounds.max().y, EPSILON);
        assertEquals(2.0, bounds.max().z, EPSILON);
    }

    @Test
    void appliesTheStructurePoseToTheLocalBox() {
        final Aabb bounds = StructureRenderData.worldBounds(
                new IntBounds3(-1, 0, 0, 1, 1, 1),
                Transform3d.of(new Vector3d(10.0, 64.0, -3.0)), 1.0);

        assertEquals(9.0, bounds.min().x, EPSILON);
        assertEquals(64.0, bounds.min().y, EPSILON);
        assertEquals(-3.0, bounds.min().z, EPSILON);
        assertEquals(11.0, bounds.max().x, EPSILON);
        assertEquals(65.0, bounds.max().y, EPSILON);
        assertEquals(-2.0, bounds.max().z, EPSILON);
    }
}
