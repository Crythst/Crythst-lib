package io.mikaple.crythst.integration.portal;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;

/**
 * Sampled plane-crossing test used by traversal and the client transition.
 *
 * <p>Traversal is bidirectional. Both directions use the same rigid
 * {@link PortalTransform}: a front-to-back crossing exits on the destination
 * front, a back-to-front crossing exits on the destination back, and the
 * transformed velocity points away from the plane in both cases. The rectangle
 * check is shared by both directions.</p>
 *
 * <p>The test is a segment/plane intersection between the previous and current
 * sample points, followed by a rectangle check at the intersection. It does not
 * use a thick slab, so a slow-moving player can physically walk into the portal
 * and only traverses when the sampled point (the eye for players) actually
 * passes the plane.</p>
 *
 * <p>Pure JOML so it can be unit tested without bootstrapping Minecraft.</p>
 */
public final class PortalCrossing {
    /** Numerical slack when testing the rectangle border. */
    private static final double EDGE_EPSILON = 1.0e-7;

    private PortalCrossing() {
    }

    /**
     * One segment/plane crossing. {@code inside} tells whether the intersection
     * lies inside the portal rectangle; {@code frontToBack} selects the
     * traversal transform.
     */
    public record Crossing(double before, double after, Vector3d point,
                           boolean inside, boolean frontToBack) {
    }

    /**
     * Finds a plane crossing in either direction. The returned record is also
     * useful for rate-limited diagnostics when the crossing missed the
     * rectangle.
     */
    public static @Nullable Crossing findCrossing(PortalPlane plane,
                                                  Vector3dc previous,
                                                  Vector3dc current) {
        if (plane == null || previous == null || current == null) {
            return null;
        }
        final double before = plane.signedDistance(previous);
        final double after = plane.signedDistance(current);
        if (before == 0.0 && after == 0.0) {
            return null;
        }
        // Same strict side, or no movement relative to the plane.
        if ((before > 0.0 && after > 0.0) || (before < 0.0 && after < 0.0)) {
            return null;
        }
        final double denominator = before - after;
        if (denominator == 0.0 || !Double.isFinite(denominator)) {
            return null;
        }
        final double t = before / denominator;
        if (!(t >= 0.0) || t > 1.0) {
            return null;
        }

        final Vector3d point = new Vector3d(previous).lerp(current, t);
        final Vector3d local = plane.local(point);
        final boolean inside = Math.abs(local.x()) <= plane.width() * 0.5 + EDGE_EPSILON
                && Math.abs(local.y()) <= plane.height() * 0.5 + EDGE_EPSILON;
        final boolean frontToBack = before >= 0.0 && after <= 0.0;
        return new Crossing(before, after, point, inside, frontToBack);
    }

    /**
     * Returns the world-space intersection point for a front-to-back crossing
     * inside the rectangle, or {@code null} otherwise. Kept for the original
     * one-direction API/tests.
     */
    public static @Nullable Vector3d frontToBackCrossing(PortalPlane plane,
                                                         Vector3dc previous,
                                                         Vector3dc current) {
        final Crossing crossing = findCrossing(plane, previous, current);
        return crossing != null && crossing.inside() && crossing.frontToBack()
                ? crossing.point() : null;
    }

    /** Convenience boolean form of {@link #frontToBackCrossing}. */
    public static boolean crossesFrontToBack(PortalPlane plane,
                                             Vector3dc previous,
                                             Vector3dc current) {
        return frontToBackCrossing(plane, previous, current) != null;
    }

    /** True for an inside crossing in either direction (bidirectional traversal). */
    public static boolean crosses(PortalPlane plane,
                                  Vector3dc previous,
                                  Vector3dc current) {
        final Crossing crossing = findCrossing(plane, previous, current);
        return crossing != null && crossing.inside();
    }
}
