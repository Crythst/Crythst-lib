package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityStructureSolverTest {
    private static final double MARGIN = 0.02;

    private static VoxelBox box(int x, int y, int z, int sx, int sy, int sz) {
        return new VoxelBox(x, y, z, sx, sy, sz, PhysicsMaterial.STONE, 1.0);
    }

    private static Aabb entityBox(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    @Test
    void standingOnFloorIsPushedUp() {
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 1.7, 0.8),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 64);

        assertNotNull(hit);
        assertTrue(hit.normal().y > 0.99);
        assertEquals(0.1, hit.penetration(), 1.0e-6);
        assertEquals(0.12, hit.offset().y, 1.0e-6);
        assertEquals(0.0, hit.offset().x, 1.0e-6);
        assertEquals(0.0, hit.offset().z, 1.0e-6);
    }

    @Test
    void embeddedSideIsPushedAlongShallowestAxis() {
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.85, 0.1, 0.2, 1.45, 1.9, 0.8),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 2, 1)),
                1.0, MARGIN, 64);

        assertNotNull(hit);
        assertTrue(hit.normal().x > 0.99);
        assertEquals(0.15, hit.penetration(), 1.0e-6);
        assertEquals(0.17, hit.offset().x, 1.0e-6);
    }

    @Test
    void clearEntityIsNotMoved() {
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.2, 1.3, 0.2, 0.8, 2.1, 0.8),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 64);

        assertNull(hit);
    }

    @Test
    void rotatedStructureUsesWorldSpaceNormal() {
        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(90.0));
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 0.0, 0.0), rotation);

        final var hit = EntityStructureSolver.resolve(
                entityBox(10.2, 0.9, -0.8, 10.8, 1.7, -0.2),
                pose,
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 64);

        assertNotNull(hit);
        assertTrue(hit.normal().y > 0.99);
        assertEquals(0.1, hit.penetration(), 1.0e-6);
        assertEquals(0.12, hit.offset().y, 1.0e-6);
    }

    @Test
    void straddlingTwoFloorBoxesStillPrefersUp() {
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.8, 0.95, 0.2, 1.4, 1.5, 0.8),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1), box(1, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 64);

        assertNotNull(hit);
        assertTrue(hit.normal().y > 0.99);
        assertEquals(0.05, hit.penetration(), 1.0e-6);
    }

    @Test
    void exactOverlapPrefersUpwardTieBreak() {
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 64);

        assertNotNull(hit);
        assertTrue(hit.normal().y > 0.99);
    }

    @Test
    void distantStructureIsRejectedByLocalQuery() {
        final Transform3d pose = Transform3d.of(new Vector3d(100.0, 0.0, 0.0));
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 1.7, 0.8),
                pose,
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 64);

        assertNull(hit);
    }

    @Test
    void blockScaleChangesVoxelSize() {
        // A 2x2x2 voxel structure with a half-metre voxel is 1x1x1 metres.
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 1.7, 0.8),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 2, 2, 2)),
                0.5, MARGIN, 64);

        assertNotNull(hit);
        assertTrue(hit.normal().y > 0.99);
        assertEquals(0.1, hit.penetration(), 1.0e-6);
    }

    @Test
    void boxBudgetIsRespected() {
        final var hit = EntityStructureSolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 1.7, 0.8),
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, MARGIN, 0);

        assertNull(hit);
    }
}
