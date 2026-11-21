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

    @Test
    void previousSidePreventsPushingThroughThinWall() {
        final Aabb current = entityBox(0.3, 0.1, 0.2, 0.9, 1.9, 0.8);
        final List<VoxelBox> wall = List.of(box(0, 0, 0, 1, 2, 1));

        final var withoutHint = EntityStructureSolver.resolve(
                current, Transform3d.identity(), wall, 1.0, MARGIN, 64);
        assertNotNull(withoutHint);
        assertTrue(withoutHint.normal().x > 0.99,
                "without a hint the far side wins: " + withoutHint.normal());

        // Previous centre is on the -X side of the wall, so the entity came
        // from there; the correction must push it back rather than through.
        final var withHint = EntityStructureSolver.resolve(
                current, new Vector3d(0.4, 1.0, 0.5),
                Transform3d.identity(), wall, 1.0, MARGIN, 64);
        assertNotNull(withHint);
        assertTrue(withHint.normal().x < -0.99,
                "hint should choose the entry side, got " + withHint.normal());
        assertTrue(withHint.offset().x < 0.0);
    }

    @Test
    void structurePoseAndBodyPoseProduceTheSameWorldBox() {
        final Quaterniond rotation = new Quaterniond()
                .rotateX(0.37)
                .rotateY(-1.1)
                .rotateZ(0.83);
        final Vector3d structurePosition = new Vector3d(3.0, 4.0, 5.0);
        final Transform3d structurePose = Transform3d.of(structurePosition, rotation);
        final double blockScale = 0.5;
        final Vector3d centreOfMassOffset = new Vector3d(1.2, 0.7, 2.1);
        final Vector3d voxelCentreMetres = new Vector3d(2.4, 1.0, 0.6).mul(blockScale);

        // Render/collision convention: structurePose * (voxel * blockScale).
        final Vector3d viaStructurePose = structurePose.transformPoint(voxelCentreMetres);
        // Body convention: bodyPose * (voxel * blockScale - COM).
        final Transform3d bodyPose = Transform3d.of(
                structurePosition.add(new Vector3d(centreOfMassOffset).rotate(rotation)),
                rotation);
        final Vector3d viaBodyPose = bodyPose.transformPoint(
                new Vector3d(voxelCentreMetres).sub(centreOfMassOffset));

        assertEquals(viaStructurePose.x, viaBodyPose.x, 1.0e-12);
        assertEquals(viaStructurePose.y, viaBodyPose.y, 1.0e-12);
        assertEquals(viaStructurePose.z, viaBodyPose.z, 1.0e-12);
    }

    @Test
    void signPointsOutOfTheRotatedFace() {
        final Quaterniond rotation = new Quaterniond()
                .rotateX(0.41)
                .rotateY(0.93)
                .rotateZ(-0.37);
        final Transform3d pose = Transform3d.of(new Vector3d(2.0, -1.0, 6.0), rotation);
        final double scale = 0.5;
        final VoxelBox cube = box(0, 0, 0, 6, 6, 6);

        // Inside the box near the local +X face: local centre (2.75, 1.25, 1.25).
        final Vector3d positiveSide = pose.transformPoint(new Vector3d(2.75, 1.25, 1.25));
        final var positiveHit = EntityStructureSolver.resolve(
                Aabb.fromCenterHalfExtents(positiveSide, new Vector3d(0.2, 0.2, 0.2)),
                pose, List.of(cube), scale, MARGIN, 64);
        assertNotNull(positiveHit);
        final Vector3d expectedPositive = new Vector3d(1.0, 0.0, 0.0).rotate(rotation);
        assertVectorEquals(expectedPositive, positiveHit.normal(), 1.0e-9);
        final double projectedPositive = 0.2 * (Math.abs(expectedPositive.x)
                + Math.abs(expectedPositive.y) + Math.abs(expectedPositive.z));
        assertEquals(0.25 + projectedPositive, positiveHit.penetration(), 1.0e-9);

        // Inside the box near the local -X face: local centre (0.25, 1.25, 1.25).
        final Vector3d negativeSide = pose.transformPoint(new Vector3d(0.25, 1.25, 1.25));
        final var negativeHit = EntityStructureSolver.resolve(
                Aabb.fromCenterHalfExtents(negativeSide, new Vector3d(0.2, 0.2, 0.2)),
                pose, List.of(cube), scale, MARGIN, 64);
        assertNotNull(negativeHit);
        final Vector3d expectedNegative = new Vector3d(-1.0, 0.0, 0.0).rotate(rotation);
        assertVectorEquals(expectedNegative, negativeHit.normal(), 1.0e-9);
        final double projectedNegative = 0.2 * (Math.abs(expectedNegative.x)
                + Math.abs(expectedNegative.y) + Math.abs(expectedNegative.z));
        assertEquals(0.25 + projectedNegative, negativeHit.penetration(), 1.0e-9);
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual, double tolerance) {
        assertEquals(expected.x, actual.x, tolerance);
        assertEquals(expected.y, actual.y, tolerance);
        assertEquals(expected.z, actual.z, tolerance);
    }
}
