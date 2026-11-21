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
 * <p>A portal is not a mirror: an entity enters the <em>front</em> face of the
 * entry and leaves the <em>front</em> face of the exit, moving away from the
 * exit surface. The corresponding proper rotation therefore maps</p>
 *
 * <pre>
 *     entry.normal -&gt; -exit.normal
 *     entry.up     -&gt;  exit.up
 *     entry.right  -&gt; -exit.right
 * </pre>
 *
 * <p>(the right vector follows from the other two because both bases are
 * right-handed). Equivalently, in the entry plane's local frame the exit
 * coordinates of a point are {@code (-x, y, -z)}: a 180 degree yaw turn about
 * the up axis. This is a pure rotation with determinant {@code +1}, so no
 * object is mirrored while still emerging from the destination's front.</p>
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
        return new Matrix3d(
                r.x, u.x, n.x,
                r.y, u.y, n.y,
                r.z, u.z, n.z);
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
