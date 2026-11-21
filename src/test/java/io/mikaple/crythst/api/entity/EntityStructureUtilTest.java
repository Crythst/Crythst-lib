package io.mikaple.crythst.api.entity;

import io.mikaple.crythst.math.Transform3d;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityStructureUtilTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void projectionRoundTripsThroughAnOrientedFrame() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(12.0, 4.0, -7.0),
                new Quaterniond().rotateY(Math.toRadians(35.0)).rotateX(Math.toRadians(-20.0)));
        final Vector3d anchor = new Vector3d(0.0, 0.9, 0.0);
        final Vector3d world = new Vector3d(12.5, 5.2, -6.1);

        final Vector3d local = EntityStructureUtil.projectToLocal(
                pose, world, anchor, new Vector3d());
        final Vector3d roundTrip = EntityStructureUtil.projectToWorld(
                pose, local, anchor, new Vector3d());

        assertEquals(world.x, roundTrip.x, EPSILON);
        assertEquals(world.y, roundTrip.y, EPSILON);
        assertEquals(world.z, roundTrip.z, EPSILON);
    }

    @Test
    void identityFrameWithZeroAnchorIsIdentity() {
        final Vector3d world = new Vector3d(1.5, -2.0, 3.25);
        final Vector3d local = EntityStructureUtil.projectToLocal(
                Transform3d.IDENTITY, world, new Vector3d(), new Vector3d());
        assertEquals(world.x, local.x, EPSILON);
        assertEquals(world.y, local.y, EPSILON);
        assertEquals(world.z, local.z, EPSILON);
    }
}
