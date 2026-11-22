package io.mikaple.crythst.integration.portal;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void preservesPositiveAndNegativeDirectionsAcrossTheLink() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final Vector3d positiveVelocity = new Vector3d(0.0, 0.0, 1.0);
        final Vector3d negativeVelocity = new Vector3d(0.0, 0.0, -1.0);

        assertTrue(transform.transformDirection(positiveVelocity).dot(exit().normal()) > 0.0);
        assertTrue(transform.transformDirection(negativeVelocity).dot(exit().normal()) < 0.0);
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
    void predictionFromTheOldSideStillPlacesThePlayerOnTheCrossedSide() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        for (int side : new int[]{-1, 1}) {
            final Vector3d staleEye = entry().origin().fma(-side * 0.2, entry().normal());
            final Vector3d feet = PortalTeleportMath.playerFeetAfterCrossing(
                    transform, staleEye, 1.62, 0.9, side > 0);
            assertTrue(exit().signedDistance(new Vector3d(feet).add(0, 1.62, 0)) * side > 0.0);
            assertTrue(exit().signedDistance(new Vector3d(feet).add(0, 0.9, 0)) * side > 0.0);
        }
    }

    @Test
    void cameraInterpolationCrossesTheExitPlaneInEitherDirection() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final double eyeHeight = 1.62;
        for (int side : new int[]{-1, 1}) {
            final Vector3d startEye = entry().origin().fma(-side * 0.3, entry().normal());
            final Vector3d movement = new Vector3d(entry().normal()).mul(side * 0.4);
            final Vector3d endEye = new Vector3d(startEye).add(movement);
            final Vector3d exitFeet = PortalTeleportMath.playerFeetAfterCrossing(
                    transform, endEye, eyeHeight, 0.9, side > 0);
            final Vector3d exitEndEye = new Vector3d(exitFeet).add(0, eyeHeight, 0);
            final Vector3d exitStartEye = PortalTeleportMath.previousFeetAfterCrossing(
                    transform, exitFeet, movement).add(0, eyeHeight, 0);
            final Vector3d beforeCrossing = new Vector3d(exitStartEye).lerp(exitEndEye, 0.25);
            final Vector3d afterCrossing = new Vector3d(exitStartEye).lerp(exitEndEye, 0.95);

            assertVectorEquals(transform.transformDirection(movement),
                    new Vector3d(exitEndEye).sub(exitStartEye));
            assertFalse(PortalTeleportMath.eyeReachedExit(exit(), beforeCrossing, side > 0));
            assertTrue(PortalTeleportMath.eyeReachedExit(exit(), afterCrossing, side > 0));
        }
    }

    @Test
    void exitBiasFallsBackToEntrySideForStationaryObjects() {
        final Vector3d biasFront = PortalTeleportMath.exitBias(
                exit(), new Vector3d(), new Vector3d(0.0, 0.0, -0.2));
        final Vector3d biasBack = PortalTeleportMath.exitBias(
                exit(), new Vector3d(), new Vector3d(0.0, 0.0, 0.2));
        assertTrue(biasFront.dot(exit().normal()) < 0.0);
        assertTrue(biasBack.dot(exit().normal()) > 0.0);
    }

    @Test
    void rejectsNonFiniteNetworkSamples() {
        final Vector3d finite = new Vector3d(1.0, 2.0, 3.0);
        assertTrue(PortalTeleportMath.isFinite(finite));
        assertFalse(PortalTeleportMath.isFinite(new Vector3d(Double.NaN, 0.0, 0.0)));
        assertFalse(PortalTeleportMath.isFinite(
                new Vector3d(0.0, Double.POSITIVE_INFINITY, 0.0)));
        assertFalse(PortalTeleportMath.isFiniteSample(
                finite, new Vector3d(0.0, 0.0, Double.NaN), finite));
        assertFalse(PortalTeleportMath.isFiniteSample(finite, finite, null));
        assertTrue(PortalTeleportMath.isFiniteSample(finite, finite, finite));
    }

    @Test
    void clientCrossingMustBeWithinTheServerRadius() {
        final Vector3d centre = new Vector3d(0.0, 64.0, 0.0);
        assertTrue(PortalTeleportMath.isWithin(new Vector3d(1.5, 64.0, 0.0), centre, 2.5));
        assertTrue(PortalTeleportMath.isWithin(new Vector3d(0.0, 66.5, 0.0), centre, 2.5));
        assertFalse(PortalTeleportMath.isWithin(new Vector3d(2.6, 64.0, 0.0), centre, 2.5));
        assertFalse(PortalTeleportMath.isWithin(new Vector3d(0.0, 64.0, -2.6), centre, 2.5));
        assertFalse(PortalTeleportMath.isWithin(null, centre, 2.5));
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
