package io.mikaple.crythst.integration.portal;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traversal regression tests: a portal is entered only when the sampled point
 * (the eye for players) crosses from the front side to the back side inside
 * the rectangle, not when the body centre merely enters a thick slab.
 */
class PortalTraversalTest {
    private static final double EPS = 1.0e-9;

    private static PortalPlane plane() {
        return new PortalPlane(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    @Test
    void detectsFrontToBackCrossingInsideRectangle() {
        final Vector3d previous = new Vector3d(0.25, 64.5, 1.0);
        final Vector3d current = new Vector3d(0.25, 64.5, -1.0);
        final Vector3d crossing = PortalCrossing.frontToBackCrossing(
                plane(), previous, current);

        assertNotNull(crossing);
        assertEquals(0.25, crossing.x, EPS);
        assertEquals(64.5, crossing.y, EPS);
        assertEquals(0.0, crossing.z, EPS);
    }

    @Test
    void doesNotTriggerWhileTheEyeIsStillOnTheFrontSide() {
        final Vector3d previous = new Vector3d(0.0, 64.5, 0.35);
        final Vector3d near = new Vector3d(0.0, 64.5, 0.20);
        final Vector3d stillFront = new Vector3d(0.0, 64.5, 0.05);

        assertFalse(PortalCrossing.crossesFrontToBack(plane(), previous, near));
        assertFalse(PortalCrossing.crossesFrontToBack(plane(), near, stillFront));

        // The half-body-on-each-side moment: the eye only crosses now.
        final Vector3d behind = new Vector3d(0.0, 64.5, -0.02);
        assertTrue(PortalCrossing.crossesFrontToBack(plane(), stillFront, behind));
    }

    @Test
    void rejectsBackToFrontCrossing() {
        assertNull(PortalCrossing.frontToBackCrossing(plane(),
                new Vector3d(0.0, 64.5, -1.0),
                new Vector3d(0.0, 64.5, 1.0)));
    }

    @Test
    void rejectsCrossingOutsideTheRectangle() {
        assertNull(PortalCrossing.frontToBackCrossing(plane(),
                new Vector3d(1.5, 64.5, 1.0),
                new Vector3d(1.5, 64.5, -1.0)));
        assertNull(PortalCrossing.frontToBackCrossing(plane(),
                new Vector3d(0.0, 66.0, 1.0),
                new Vector3d(0.0, 66.0, -1.0)));
    }

    @Test
    void doesNotRetriggerWhenRestingExactlyOnThePlane() {
        assertNull(PortalCrossing.frontToBackCrossing(plane(),
                new Vector3d(0.0, 64.5, 0.0),
                new Vector3d(0.0, 64.5, -0.5)));
    }

    @Test
    void transformedEyeLandsOnExitPlaneAndLeavesFromItsFront() {
        final PortalPlane entry = plane();
        final PortalPlane exit = new PortalPlane(
                new Vector3d(20.0, 70.0, -5.0),
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0),
                2.0, 3.0);
        final PortalTransform transform = PortalTransform.between(entry, exit);

        // Eye exactly on the entry plane at the crossing instant.
        final Vector3d eye = new Vector3d(0.25, 64.5, 0.0);
        final Vector3d transformedEye = transform.transformPoint(eye);
        assertEquals(0.0, exit.signedDistance(transformedEye), EPS);

        // Walking front-to-back through the entry exits towards the exit's
        // front (+normal), not into its back.
        final Vector3d velocity = new Vector3d(0.0, 0.0, -1.0);
        final Vector3d transformedVelocity = transform.transformDirection(velocity);
        assertTrue(transformedVelocity.dot(exit.normal()) > 0.0,
                "front-to-back traversal must leave through the exit front");
    }
}
