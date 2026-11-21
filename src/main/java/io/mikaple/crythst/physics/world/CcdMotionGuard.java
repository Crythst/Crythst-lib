package io.mikaple.crythst.physics.world;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import org.joml.Vector3d;

/**
 * Enables continuous collision detection on bodies that move far enough in one
 * tick to tunnel through thin terrain.
 *
 * <p>CCD is only switched on; it is never switched off. Bullet's CCD motion
 * threshold keeps a resting body asleep and costs nothing while it is slow, so
 * a single fast tick does not leave the body permanently unstable. This policy
 * is intentionally independent of {@code StructureDef#ccd}: explicitly
 * configured CCD is preserved, while the default (false) no longer allows a
 * fast structure to pass through the ground.</p>
 */
public final class CcdMotionGuard {
    /** Always CCD when a body travels more than this fraction of its smallest half extent. */
    private static final double EXTENT_FRACTION = 0.5;

    /** Lower bound used for very thin or degenerate shapes. */
    private static final double MIN_MOTION = 0.05;

    private CcdMotionGuard() {
    }

    /**
     * @param secondsAhead motion horizon until the next physics advance
     * @return number of bodies that received CCD in this call
     */
    public static int update(PhysicsWorld world, double secondsAhead) {
        if (world == null || world.closed()) {
            return 0;
        }
        final double horizon = Double.isFinite(secondsAhead) && secondsAhead > 0.0
                ? secondsAhead : 1.0 / 20.0;
        int changed = 0;
        for (BodyHandle body : world.bodies()) {
            if (body.type() != BodyType.DYNAMIC
                    || body.userData() == TerrainBody.INSTANCE
                    || body.ccd()
                    || body.removeRequested()) {
                continue;
            }
            final double travel = travelPerHorizon(body, horizon);
            final double threshold = Math.max(MIN_MOTION, minimumHalfExtent(body) * EXTENT_FRACTION);
            if (travel > threshold) {
                // A negative radius asks the backend to derive a swept sphere
                // from the shape's local bounds.
                body.setCcd(true, -1.0);
                changed++;
            }
        }
        return changed;
    }

    private static double travelPerHorizon(BodyHandle body, double seconds) {
        final Vector3d linear = body.linearVelocity();
        final Vector3d angular = body.angularVelocity();
        final Aabb local = body.shape().localBounds();
        final Vector3d half = local.halfExtents();
        final double radius = Math.sqrt(half.x * half.x + half.y * half.y + half.z * half.z);
        return (linear.length() + angular.length() * radius) * seconds;
    }

    private static double minimumHalfExtent(BodyHandle body) {
        final Vector3d half = body.shape().localBounds().halfExtents();
        final double smallest = Math.min(half.x, Math.min(half.y, half.z));
        return Double.isFinite(smallest) && smallest > 0.0 ? smallest : MIN_MOTION;
    }
}
