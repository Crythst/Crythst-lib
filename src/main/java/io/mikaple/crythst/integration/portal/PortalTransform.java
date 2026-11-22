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
 * <p>The proper rotation maps entry right, up and positive travel direction
 * to the corresponding exit axes. Local coordinates are preserved, so a
 * camera before the entry plane sees the world beyond the exit plane, and
 * continuing through the entry leaves the exit in the same local direction.</p>
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

    /** Maps the entry basis to the exit basis for either direction of travel. */
    public static PortalTransform between(PortalPlane entry, PortalPlane exit) {
        if (entry == null || exit == null) {
            throw new IllegalArgumentException("Both portal planes are required");
        }
        final Matrix3d source = basis(entry);
        final Matrix3d destination = basis(exit);
        final Matrix3d rotationMatrix = destination.mul(source.transpose());
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
