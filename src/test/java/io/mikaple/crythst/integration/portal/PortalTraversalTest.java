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
 * (the eye for players) crosses the plane inside the rectangle, in either
 * direction, not when the body centre merely enters a thick slab or walks along
 * the plane.
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
    void detectsBackToFrontCrossingInsideRectangle() {
        final PortalCrossing.Crossing crossing = PortalCrossing.findCrossing(plane(),
                new Vector3d(0.25, 64.5, -1.0),
                new Vector3d(0.25, 64.5, 1.0));

        assertNotNull(crossing);
        assertTrue(crossing.inside());
        assertFalse(crossing.frontToBack());
        // The legacy one-way API still reports only front-to-back crossings.
        assertNull(PortalCrossing.frontToBackCrossing(plane(),
                new Vector3d(0.0, 64.5, -1.0),
                new Vector3d(0.0, 64.5, 1.0)));
        assertTrue(PortalCrossing.crosses(plane(),
                new Vector3d(0.0, 64.5, -1.0),
                new Vector3d(0.0, 64.5, 1.0)));
    }

    @Test
    void bodyCentreInsideWhileEyeIsAboveStillTraverses() {
        // Portal centred at y=64 with height 2 spans y=63..65. A player eye
        // at y=66 is outside the rectangle, while the body centre at y=64 is
        // inside. Traversal samples the body centre, so the crossing counts.
        final PortalPlane lowPortal = new PortalPlane(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 2.0);
        final Vector3d eye = new Vector3d(0.0, 66.0, 0.0);
        final Vector3d bodyCentre = new Vector3d(0.0, 64.0, 0.0);
        assertFalse(lowPortal.contains(eye, 0.35));
        assertTrue(lowPortal.contains(bodyCentre, 0.35));

        final PortalCrossing.Crossing crossing = PortalCrossing.findCrossing(
                lowPortal,
                new Vector3d(bodyCentre.x, bodyCentre.y, bodyCentre.z + 1.0),
                new Vector3d(bodyCentre.x, bodyCentre.y, bodyCentre.z - 1.0));
        assertNotNull(crossing);
        assertTrue(crossing.inside());
    }

    @Test
    void doesNotTriggerWalkingAlongOrAroundThePlane() {
        // Moving parallel to the plane keeps the signed distance unchanged.
        assertNull(PortalCrossing.findCrossing(plane(),
                new Vector3d(-0.8, 64.5, 0.5),
                new Vector3d(0.8, 64.5, 0.5)));
        assertNull(PortalCrossing.findCrossing(plane(),
                new Vector3d(-0.8, 64.5, -0.5),
                new Vector3d(0.8, 64.5, -0.5)));

        // Crossing the infinite plane outside the rectangle does not traverse.
        assertFalse(PortalCrossing.crosses(plane(),
                new Vector3d(1.6, 64.5, 0.5),
                new Vector3d(1.6, 64.5, -0.5)));
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
    void doesNotTriggerWhenBothSamplesAreOnTheSameSide() {
        assertNull(PortalCrossing.findCrossing(plane(),
                new Vector3d(0.0, 64.5, 0.5),
                new Vector3d(0.0, 64.5, 0.4)));
        assertNull(PortalCrossing.findCrossing(plane(),
                new Vector3d(0.0, 64.5, -0.5),
                new Vector3d(0.0, 64.5, -0.4)));
        assertNull(PortalCrossing.findCrossing(plane(),
                new Vector3d(0.0, 64.5, 0.0),
                new Vector3d(0.0, 64.5, 0.0)));
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

        // A front-to-back crossing leaves through the exit front (+normal).
        final Vector3d frontVelocity = new Vector3d(0.0, 0.0, -1.0);
        assertTrue(transform.transformDirection(frontVelocity).dot(exit.normal()) > 0.0,
                "front-to-back traversal must leave through the exit front");

        // A back-to-front crossing leaves through the corresponding exit back,
        // still moving away from the plane (the same rigid transform, so the
        // view stays continuous across the crossing).
        final Vector3d backVelocity = new Vector3d(0.0, 0.0, 1.0);
        assertTrue(transform.transformDirection(backVelocity).dot(exit.normal()) < 0.0,
                "back-to-front traversal must leave through the exit back");
    }
}
