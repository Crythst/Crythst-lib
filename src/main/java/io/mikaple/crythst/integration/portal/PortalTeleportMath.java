package io.mikaple.crythst.integration.portal;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pure rigid-body traversal math shared by players, entities and physics
 * bodies.
 *
 * <p>The same {@link PortalTransform} handles both traversal directions (see
 * {@link PortalTransform}): the transform is applied to the <em>current</em>
 * pose after the crossing sample, so the object appears on the side of the exit
 * that it would have reached by walking through the opening. Velocity,
 * orientation, up/gravity direction and angular velocity are rotated by the
 * same quaternion. This is what keeps a tilted or moving portal continuous.</p>
 */
public final class PortalTeleportMath {
    private PortalTeleportMath() {
    }

    /**
     * One rigid pose plus linear/angular velocity and an up hint.
     * Implementations may omit the up hint by passing {@code null}.
     */
    public record Pose(Vector3d position, Quaterniond orientation,
                       Vector3d velocity, Vector3d angularVelocity,
                       Vector3d up) {
        public Pose {
            position = new Vector3d(position);
            orientation = new Quaterniond(orientation).normalize();
            velocity = new Vector3d(velocity);
            angularVelocity = new Vector3d(angularVelocity);
            up = up == null ? null : new Vector3d(up).normalize();
        }
    }

    public static Pose transform(PortalTransform transform, Pose state) {
        final Vector3d up = state.up() == null ? null
                : transform.transformDirection(state.up());
        return new Pose(
                transform.transformPoint(state.position()),
                transform.transformRotation(state.orientation()),
                transform.transformDirection(state.velocity()),
                transform.transformDirection(state.angularVelocity()),
                up);
    }

    /**
     * Player feet placement for the current camera geometry.
     *
     * <p>Vanilla's camera is {@code feet + eyeHeight * worldUp}. To make the
     * camera land exactly on {@code T(eye)}, place the feet there minus the
     * same world-up offset. A fully tilted-gravity traversal is outside this
     * milestone; the up vector returned by {@link #transform} is still available
     * for callers that can consume it.</p>
     */
    public static Vector3d playerFeetForTransformedEye(PortalTransform transform,
                                                       Vector3dc eye,
                                                       double eyeHeight) {
        return transform.transformPoint(eye).sub(0.0, eyeHeight, 0.0);
    }

    /**
     * Unit exit-side bias for non-player entities. The bias follows the
     * transformed velocity; if the object is almost stationary it falls back to
     * the side of the entry plane it came from.
     */
    public static Vector3d exitBias(PortalPlane destination, Vector3dc transformedVelocity,
                                    Vector3dc entryLocal) {
        final Vector3d normal = destination.normal();
        double side = Math.signum(transformedVelocity.dot(normal));
        if (side == 0.0) {
            side = -Math.signum(entryLocal.z());
        }
        if (side == 0.0) {
            side = 1.0;
        }
        return normal.mul(side);
    }

    /**
     * Swept point test: returns the segment parameter in {@code [0,1]} at which
     * the point crosses the plane, or {@code NaN}.
     */
    public static double segmentPlaneParameter(Vector3dc previous, Vector3dc current,
                                               Vector3dc planeOrigin,
                                               Vector3dc planeNormal) {
        final double before = new Vector3d(previous).sub(planeOrigin).dot(planeNormal);
        final double after = new Vector3d(current).sub(planeOrigin).dot(planeNormal);
        if ((before > 0.0 && after > 0.0) || (before < 0.0 && after < 0.0)) {
            return Double.NaN;
        }
        final double denominator = before - after;
        if (Math.abs(denominator) < 1.0e-12) {
            return Double.NaN;
        }
        final double t = before / denominator;
        return t >= 0.0 && t <= 1.0 ? t : Double.NaN;
    }

    /** True when the object is moving away from the exit plane. */
    public static boolean movingAwayFrom(PortalPlane destination, Vector3dc velocity) {
        return velocity.dot(destination.normal()) > 0.0;
    }

    /**
     * Swept crossing test used for projectiles and other fast movers. The
     * segment, not just the two endpoints, is tested against the opening.
     */
    public static boolean sweptCrossing(PortalShape shape, Vector3dc previous,
                                        Vector3dc current) {
        final PortalCrossing.Crossing crossing =
                PortalCrossing.findCrossing(shape, previous, current);
        return crossing != null && crossing.inside();
    }

    /**
     * True while an object is still inside the destination opening and moving
     * through/away from it. The server cooldown is the primary anti-return
     * mechanism; this predicate documents and tests the secondary guard.
     */
    public static boolean insideExitSafeZone(PortalPlane destination, Vector3dc position,
                                             Vector3dc velocity, double thickness) {
        return destination.contains(position, thickness)
                && velocity.dot(destination.normal()) >= 0.0;
    }

    /** Small nudge so an entity does not re-enter the exit on the next tick. */
    public static Vector3d applyExitBias(Vector3dc position, Vector3dc bias, double distance) {
        return new Vector3d(position).fma(distance, bias);
    }

    public static Quaterniondc rotation(PortalTransform transform, Quaterniondc orientation) {
        return transform.transformRotation(orientation);
    }
}
