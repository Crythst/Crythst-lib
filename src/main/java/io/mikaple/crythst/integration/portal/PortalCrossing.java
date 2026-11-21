package io.mikaple.crythst.integration.portal;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;

/**
 * Sampled plane-crossing test used by traversal and the client transition.
 *
 * <p>A portal is entered from its visible front ({@code +normal}) side and
 * exits from the destination's front side. {@link PortalTransform} relies on
 * that direction: it maps the entry normal to the <em>negative</em> exit
 * normal, so a front-to-back crossing maps to a direction leaving the exit
 * surface. This helper therefore accepts only front-to-back crossings.</p>
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
     * Returns the world-space intersection point when the segment
     * {@code previous -> current} crosses {@code plane} from the front side to
     * the back side inside the portal rectangle, or {@code null} otherwise.
     */
    public static @Nullable Vector3d frontToBackCrossing(PortalPlane plane,
                                                         Vector3dc previous,
                                                         Vector3dc current) {
        if (plane == null || previous == null || current == null) {
            return null;
        }
        final double before = plane.signedDistance(previous);
        final double after = plane.signedDistance(current);
        // Start strictly in front and end on/behind the plane. The strict
        // "before > 0" prevents an object resting on the plane from triggering
        // on every tick, and the direction check keeps the exit in front of
        // the destination portal.
        if (!(before > 0.0) || after > 0.0) {
            return null;
        }
        final double denominator = before - after;
        if (!(denominator > 0.0) || !Double.isFinite(denominator)) {
            return null;
        }
        final double t = before / denominator;
        if (!(t >= 0.0) || t > 1.0) {
            return null;
        }

        final Vector3d point = new Vector3d(previous).lerp(current, t);
        final Vector3d local = plane.local(point);
        if (Math.abs(local.x()) > plane.width() * 0.5 + EDGE_EPSILON
                || Math.abs(local.y()) > plane.height() * 0.5 + EDGE_EPSILON) {
            return null;
        }
        return point;
    }

    /** Convenience boolean form of {@link #frontToBackCrossing}. */
    public static boolean crossesFrontToBack(PortalPlane plane,
                                             Vector3dc previous,
                                             Vector3dc current) {
        return frontToBackCrossing(plane, previous, current) != null;
    }
}
