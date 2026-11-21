package io.mikaple.crythst.integration.portal;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The rigid transform that identifies an entry portal plane with an exit
 * portal plane.
 *
 * <h2>Why the normal is flipped</h2>
 *
 * <p>A portal is not a mirror: an object entering one face leaves through the
 * corresponding face of the exit, moving away from the exit surface. The
 * proper rotation maps</p>
 *
 * <pre>
 *     entry.normal -&gt; -exit.normal
 *     entry.up     -&gt;  exit.up
 *     entry.right  -&gt; -exit.right
 * </pre>
 *
 * <p>Equivalently, local coordinates map as {@code (-x, y, -z)}: a 180 degree
 * yaw turn about the up axis, still determinant {@code +1}.</p>
 *
 * <p>The same rigid transform serves both traversal directions and the camera
 * view: a front-to-back crossing exits on the destination front with velocity
 * pointing away from it, and a back-to-front crossing exits on the destination
 * back, also moving away. Using one transform for both directions keeps the
 * view continuous across the crossing instant.</p>
 *
 * <p>The transform is rigid and scale-free ({@code 1:1}); it only maps points,
 * directions and rotations relative to the entry origin.</p>
 */
public final class PortalTransform {
    private final PortalPlane entry;
    private final PortalPlane exit;
    private final Quaterniond rotation;
    private final Matrix3d matrix;

    private PortalTransform(PortalPlane entry, PortalPlane exit, Quaterniond rotation) {
        this.entry = entry;
        this.exit = exit;
        this.rotation = rotation;
        this.matrix = new Matrix3d().rotation(rotation);
    }

    /**
     * The rigid link transform. It is bidirectional: a front-to-back crossing
     * exits on the destination front, a back-to-front crossing exits on the
     * destination back, and in both cases the transformed velocity points away
     * from the destination plane.
     */
    public static PortalTransform between(PortalPlane entry, PortalPlane exit) {
        if (entry == null || exit == null) {
            throw new IllegalArgumentException("Both portal planes are required");
        }
        final Matrix3d source = basis(entry);
        final Matrix3d destination = basis(exit);
        // S = diag(-1, 1, -1): flip local x and z (proper rotation because
        // det(B_exit) * det(S) * det(B_entry^T) = 1 * 1 * 1).
        final Matrix3d flip = new Matrix3d(
                -1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, -1.0);
        final Matrix3d rotationMatrix = new Matrix3d(destination)
                .mul(flip)
                .mul(source.transpose());
        final Quaterniond q = new Quaterniond().setFromNormalized(rotationMatrix);
        return new PortalTransform(entry, exit, q);
    }

    private static Matrix3d basis(PortalPlane plane) {
        final Vector3d r = plane.right();
        final Vector3d u = plane.up();
        final Vector3d n = plane.normal();
        // JOML Matrix3d constructor arguments are column-major: the first
        // triplet is column 0. Store right/up/normal as the three columns.
        return new Matrix3d(
                r.x, r.y, r.z,
                u.x, u.y, u.z,
                n.x, n.y, n.z);
    }

    public PortalPlane entry() {
        return entry;
    }

    public PortalPlane exit() {
        return exit;
    }

    /** Rotation applied to directions (and to local coordinates). */
    public Quaterniond rotation() {
        return new Quaterniond(rotation);
    }

    public Vector3d transformPoint(Vector3dc worldPoint) {
        final Vector3d relative = new Vector3d(worldPoint).sub(entry.origin());
        relative.rotate(rotation);
        return relative.add(exit.origin());
    }

    public Vector3d transformPoint(Vector3dc worldPoint, Vector3d dest) {
        dest.set(worldPoint).sub(entry.origin()).rotate(rotation).add(exit.origin());
        return dest;
    }

    public Vector3d transformDirection(Vector3dc direction) {
        return new Vector3d(direction).rotate(rotation);
    }

    public Vector3d transformDirection(Vector3dc direction, Vector3d dest) {
        return dest.set(direction).rotate(rotation);
    }

    /** Applies the transform to an orientation quaternion. */
    public Quaterniond transformRotation(Quaterniondc orientation) {
        return new Quaterniond(rotation).mul(orientation).normalize();
    }

    /** Transform that maps exit coordinates back to the entry. */
    public PortalTransform inverse() {
        return between(exit, entry);
    }

    /** Row-major rotation matrix, useful for renderer/debug output. */
    public Matrix3d matrix() {
        return new Matrix3d(matrix);
    }

    /**
     * Converts a direction into Minecraft yaw/pitch (degrees).
     *
     * @return {@code {yaw, pitch}} where yaw is in {@code [-180, 180]}
     */
    public static float[] yawPitch(Vector3dc direction) {
        final Vector3d dir = new Vector3d(direction);
        if (dir.lengthSquared() < 1.0e-20) {
            return new float[]{0.0f, 0.0f};
        }
        dir.normalize();
        final double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        final double pitch = Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        return new float[]{(float) yaw, (float) pitch};
    }

    /** Minecraft view direction from yaw/pitch in degrees. */
    public static Vector3d viewDirection(float yawDegrees, float pitchDegrees) {
        final double yaw = Math.toRadians(yawDegrees);
        final double pitch = Math.toRadians(pitchDegrees);
        final double cosPitch = Math.cos(pitch);
        return new Vector3d(
                -Math.sin(yaw) * cosPitch,
                -Math.sin(pitch),
                Math.cos(yaw) * cosPitch);
    }

    @Override
    public String toString() {
        return "PortalTransform[" + entry + " -> " + exit + " q=" + rotation + "]";
    }
}
