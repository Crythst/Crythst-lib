package io.mikaple.crythst.integration.portal;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTeleportMathTest {
    private static final double EPS = 1.0e-9;

    private static PortalPlane entry() {
        return new PortalPlane(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    private static PortalPlane exit() {
        return new PortalPlane(
                new Vector3d(20.0, 70.0, -5.0),
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0),
                2.0, 3.0);
    }

    @Test
    void transformsPoseVelocityOrientationAndUp() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final PortalTeleportMath.Pose input = new PortalTeleportMath.Pose(
                new Vector3d(0.25, 65.5, -0.5),
                new Quaterniond().rotateY(0.3).rotateX(-0.2),
                new Vector3d(0.4, -0.1, -2.0),
                new Vector3d(0.0, 1.5, 0.2),
                new Vector3d(0.0, 1.0, 0.0));
        final PortalTeleportMath.Pose output = PortalTeleportMath.transform(transform, input);

        assertVectorEquals(transform.transformPoint(input.position()), output.position());
        assertVectorEquals(transform.transformDirection(input.velocity()), output.velocity());
        assertVectorEquals(transform.transformDirection(input.angularVelocity()),
                output.angularVelocity());
        assertVectorEquals(transform.transformDirection(input.up()), output.up());
        assertEquals(1.0, output.orientation().dot(
                transform.transformRotation(input.orientation())), 1.0e-9);
    }

    @Test
    void traversalIsReversibleForBothDirections() {
        final PortalTransform forward = PortalTransform.between(entry(), exit());
        final PortalTeleportMath.Pose start = new PortalTeleportMath.Pose(
                new Vector3d(0.1, 64.2, 3.0),
                new Quaterniond(),
                new Vector3d(0.0, 0.0, -1.5),
                new Vector3d(),
                new Vector3d(0.0, 1.0, 0.0));
        final PortalTeleportMath.Pose atExit = PortalTeleportMath.transform(forward, start);
        final PortalTeleportMath.Pose back = PortalTeleportMath.transform(forward.inverse(), atExit);
        assertVectorEquals(start.position(), back.position());
        assertVectorEquals(start.velocity(), back.velocity());
    }

    @Test
    void frontToBackLeavesThroughExitFrontAndBackToFrontThroughExitBack() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final Vector3d frontVelocity = new Vector3d(0.0, 0.0, -1.0);
        final Vector3d backVelocity = new Vector3d(0.0, 0.0, 1.0);

        assertTrue(transform.transformDirection(frontVelocity).dot(exit().normal()) > 0.0);
        assertTrue(transform.transformDirection(backVelocity).dot(exit().normal()) < 0.0);
    }

    @Test
    void playerFeetPlaceTheCameraAtTheTransformedEye() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final Vector3d eye = new Vector3d(0.3, 65.6, 1.0);
        final double eyeHeight = 1.62;
        final Vector3d feet = PortalTeleportMath.playerFeetForTransformedEye(
                transform, eye, eyeHeight);
        final Vector3d camera = new Vector3d(feet).add(0.0, eyeHeight, 0.0);
        assertVectorEquals(transform.transformPoint(eye), camera);
    }

    @Test
    void exitBiasFallsBackToEntrySideForStationaryObjects() {
        final Vector3d biasFront = PortalTeleportMath.exitBias(
                exit(), new Vector3d(), new Vector3d(0.0, 0.0, -0.2));
        final Vector3d biasBack = PortalTeleportMath.exitBias(
                exit(), new Vector3d(), new Vector3d(0.0, 0.0, 0.2));
        assertTrue(biasFront.dot(exit().normal()) > 0.0);
        assertTrue(biasBack.dot(exit().normal()) < 0.0);
    }

    @Test
    void sweptTestFindsFastCrossings() {
        final double t = PortalTeleportMath.segmentPlaneParameter(
                new Vector3d(0.0, 0.0, 50.0),
                new Vector3d(0.0, 0.0, -50.0),
                new Vector3d(), new Vector3d(0.0, 0.0, 1.0));
        assertEquals(0.5, t, EPS);

        assertEquals(Double.NaN, PortalTeleportMath.segmentPlaneParameter(
                new Vector3d(0.0, 0.0, 2.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(), new Vector3d(0.0, 0.0, 1.0)));
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }
}
