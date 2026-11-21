package io.mikaple.crythst.api.entity;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.world.phys.Vec3;
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

    @Test
    void anchoredLocalFrameRoundTripsToTheSameWorldPosition() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(-4.0, 12.0, 8.0),
                new Quaterniond().rotateZ(Math.toRadians(70.0)).rotateY(Math.toRadians(-15.0)));
        final Vector3d anchor = new Vector3d(0.2, 1.4, -0.3);
        final Vector3d world = new Vector3d(-3.1, 13.7, 7.2);

        final Vector3d local = EntityStructureUtil.projectToLocal(
                pose, world, anchor, new Vector3d());
        final Vector3d roundTrip = EntityStructureUtil.projectToWorld(
                pose, local, anchor, new Vector3d());

        assertEquals(world.x, roundTrip.x, EPSILON);
        assertEquals(world.y, roundTrip.y, EPSILON);
        assertEquals(world.z, roundTrip.z, EPSILON);
    }

    @Test
    void lookAndVelocityDirectionsRoundTripThroughTheFrame() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(3.0, -2.0, 5.0),
                new Quaterniond().rotateX(Math.toRadians(25.0)).rotateZ(Math.toRadians(-40.0)));
        final Vector3d localLook = new Vector3d(0.3, -0.4, 0.5).normalize();
        final Vector3d localVelocity = new Vector3d(-0.12, 0.0, 0.34).mul(20.0);

        final Vector3d worldLook = pose.transformDirection(new Vector3d(localLook));
        final Vector3d worldVelocity = pose.transformDirection(new Vector3d(localVelocity));
        final Vector3d roundTripLook = pose.inverseTransformDirection(worldLook);
        final Vector3d roundTripVelocity = pose.inverseTransformDirection(worldVelocity);

        assertEquals(localLook.x, roundTripLook.x, EPSILON);
        assertEquals(localLook.y, roundTripLook.y, EPSILON);
        assertEquals(localLook.z, roundTripLook.z, EPSILON);
        assertEquals(localVelocity.x, roundTripVelocity.x, EPSILON);
        assertEquals(localVelocity.y, roundTripVelocity.y, EPSILON);
        assertEquals(localVelocity.z, roundTripVelocity.z, EPSILON);
    }

    @Test
    void eyeInterpolationUsesPreviousPositionAndCurrentEyeHeight() {
        final Vec3 eye = EntityStructureUtil.interpolateEyePosition(
                1.0, 2.0, 3.0,
                5.0, 6.0, 7.0,
                1.6, 0.25f);

        assertEquals(2.0, eye.x, EPSILON);
        assertEquals(3.0 + 1.6, eye.y, EPSILON);
        assertEquals(4.0, eye.z, EPSILON);
    }

    @Test
    void eyeInterpolationAtTheCurrentTickIsTheVanillaEyePosition() {
        final Vec3 eye = EntityStructureUtil.interpolateEyePosition(
                -8.0, 10.0, 4.0,
                2.0, 3.0, -6.0,
                1.62, 1.0f);

        assertEquals(2.0, eye.x, EPSILON);
        assertEquals(3.0 + 1.62, eye.y, EPSILON);
        assertEquals(-6.0, eye.z, EPSILON);
    }

    @Test
    void distanceWithoutALevelFallsBackToVanillaSquaredDistance() {
        final Vec3 a = new Vec3(1.0, 2.0, 3.0);
        final Vec3 b = new Vec3(4.0, 6.0, 3.0);

        assertEquals(25.0, EntityStructureUtil.distanceSquaredWithStructures(null, a, b), EPSILON);
    }

    @Test
    void squaredDistanceInAStructureFrameMatchesWorldDistance() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(12.0, 4.0, -7.0),
                new Quaterniond().rotateY(Math.toRadians(35.0)).rotateX(Math.toRadians(-20.0)));
        final Vec3 a = new Vec3(12.5, 5.2, -6.1);
        final Vec3 b = new Vec3(10.0, 3.0, -8.5);

        final double expected = a.distanceToSqr(b);
        final double actual = EntityStructureUtil.distanceSquaredInFrame(pose, a, b);

        assertEquals(expected, actual, EPSILON);
    }
}
