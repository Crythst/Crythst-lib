package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure tests for the pose/voxel -> world particle mapping. No Minecraft
 * bootstrap or live level is needed.
 */
class StructureParticleMathTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void voxelCentreFollowsTranslationAndScale() {
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 64.0, -3.0));
        final Vector3d centre = StructureParticleMath.voxelCenter(pose, 2, 4, 6, 2.0);
        assertEquals(10.0 + (2.5 * 2.0), centre.x, EPSILON);
        assertEquals(64.0 + (4.5 * 2.0), centre.y, EPSILON);
        assertEquals(-3.0 + (6.5 * 2.0), centre.z, EPSILON);
    }

    @Test
    void rotationMapsVoxelOffsetAndVelocityTogether() {
        final Quaterniond rotation = new Quaterniond().rotateY(Math.PI / 2.0);
        final Transform3d pose = Transform3d.of(new Vector3d(5.0, 7.0, 9.0), rotation);

        // Local +X of the voxel must become world -Z under a +90 degree Y turn.
        final Vector3d nearFace = StructureParticleMath.worldPosition(
                pose, 0, 0, 0, 1.0, 0.9, 0.5, 0.5);
        final Vector3d farFace = StructureParticleMath.worldPosition(
                pose, 0, 0, 0, 1.0, 0.1, 0.5, 0.5);
        // Local x = 0.9/0.1, local z = 0.5; after the turn both share world x
        // = 0.5 and differ only along world z.
        assertEquals(5.5, nearFace.x, EPSILON);
        assertEquals(9.0 - 0.9, nearFace.z, EPSILON);
        assertEquals(5.5, farFace.x, EPSILON);
        assertEquals(9.0 - 0.1, farFace.z, EPSILON);

        // Debris velocity is rotated but not translated.
        final Vector3d velocity = StructureParticleMath.debrisVelocity(
                pose, 0.9, 0.5, 0.5, 1.0);
        assertEquals(0.0, velocity.x, EPSILON);
        assertEquals(0.0, velocity.y, EPSILON);
        assertEquals(-0.4 * StructureParticleMath.DEBRIS_SPEED_FRACTION,
                velocity.z, EPSILON);
    }

    @Test
    void velocityScalesWithBlockScale() {
        final Transform3d pose = Transform3d.of(new Vector3d(100.0, 0.0, 0.0),
                new Quaterniond().rotateX(0.35).rotateZ(-0.2));
        final Vector3d unit = StructureParticleMath.debrisVelocity(
                pose, 1.0, 0.0, 0.75, 1.0);
        final Vector3d scaled = StructureParticleMath.debrisVelocity(
                pose, 1.0, 0.0, 0.75, 3.0);
        assertEquals(unit.x * 3.0, scaled.x, EPSILON);
        assertEquals(unit.y * 3.0, scaled.y, EPSILON);
        assertEquals(unit.z * 3.0, scaled.z, EPSILON);
    }

    @Test
    void localFractionStaysInsideTheVoxel() {
        final Transform3d pose = Transform3d.of(new Vector3d(1.0, 2.0, 3.0),
                new Quaterniond().rotateY(0.7).rotateX(-0.4));
        final Vector3d low = StructureParticleMath.worldPosition(
                pose, 4, -2, 8, 1.5, 0.0, 0.0, 0.0);
        final Vector3d high = StructureParticleMath.worldPosition(
                pose, 4, -2, 8, 1.5, 1.0, 1.0, 1.0);
        final Vector3d lowLocal = pose.inverseTransformPoint(low);
        final Vector3d highLocal = pose.inverseTransformPoint(high);
        assertEquals(4.0 * 1.5, lowLocal.x, EPSILON);
        assertEquals(-2.0 * 1.5, lowLocal.y, EPSILON);
        assertEquals(8.0 * 1.5, lowLocal.z, EPSILON);
        assertEquals(5.0 * 1.5, highLocal.x, EPSILON);
        assertEquals(-1.0 * 1.5, highLocal.y, EPSILON);
        assertEquals(9.0 * 1.5, highLocal.z, EPSILON);
    }

    @Test
    void invalidScaleFallsBackToOne() {
        assertEquals(1.0, StructureParticleMath.safeScale(Double.NaN), EPSILON);
        assertEquals(1.0, StructureParticleMath.safeScale(0.0), EPSILON);
        assertEquals(2.5, StructureParticleMath.safeScale(2.5), EPSILON);
    }
}
