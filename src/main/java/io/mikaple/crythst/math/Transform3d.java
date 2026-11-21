package io.mikaple.crythst.math;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * An immutable rigid transform: rotation followed by translation.
 *
 * <p>Public coordinates are always expressed in world space (double precision),
 * while physics backends may keep a local single-precision copy around a
 * movable origin. See {@code PhysicsWorld#rebaseOrigin}.</p>
 */
public record Transform3d(Vector3d position, Quaterniond rotation) {
    public static final Transform3d IDENTITY = new Transform3d(new Vector3d(), new Quaterniond());

    public Transform3d {
        position = new Vector3d(position);
        rotation = new Quaterniond(rotation);
        if (rotation.lengthSquared() < 1.0e-20) {
            rotation.identity();
        } else {
            rotation.normalize();
        }
        if (!isFinite(position)) {
            throw new IllegalArgumentException("Transform position must be finite: " + position);
        }
        if (!isFinite(rotation)) {
            throw new IllegalArgumentException("Transform rotation must be finite: " + rotation);
        }
    }

    public static Transform3d identity() {
        return IDENTITY;
    }

    public static Transform3d of(Vector3dc position) {
        return new Transform3d(new Vector3d(position), new Quaterniond());
    }

    public static Transform3d of(Vector3dc position, Quaterniondc rotation) {
        return new Transform3d(new Vector3d(position), new Quaterniond(rotation));
    }

    @Override
    public Vector3d position() {
        return new Vector3d(position);
    }

    @Override
    public Quaterniond rotation() {
        return new Quaterniond(rotation);
    }

    public Vector3d position(Vector3d dest) {
        return dest.set(position);
    }

    public Quaterniond rotation(Quaterniond dest) {
        return dest.set(rotation);
    }

    public Transform3d withPosition(Vector3dc newPosition) {
        return new Transform3d(new Vector3d(newPosition), rotation);
    }

    public Transform3d withRotation(Quaterniondc newRotation) {
        return new Transform3d(position, new Quaterniond(newRotation));
    }

    /** Returns {@code this * other} (other is applied first). */
    public Transform3d compose(Transform3d other) {
        final Vector3d composedPosition = new Vector3d(other.position).rotate(rotation).add(position);
        final Quaterniond composedRotation = new Quaterniond(rotation).mul(other.rotation);
        return new Transform3d(composedPosition, composedRotation);
    }

    public Transform3d inverse() {
        final Quaterniond inverseRotation = new Quaterniond(rotation).conjugate();
        final Vector3d inversePosition = new Vector3d(position).negate().rotate(inverseRotation);
        return new Transform3d(inversePosition, inverseRotation);
    }

    public Vector3d transformPoint(Vector3dc point) {
        return new Vector3d(point).rotate(rotation).add(position);
    }

    public Vector3d transformPoint(Vector3dc point, Vector3d dest) {
        return dest.set(point).rotate(rotation).add(position);
    }

    public Vector3d transformDirection(Vector3dc direction) {
        return new Vector3d(direction).rotate(rotation);
    }

    public Vector3d inverseTransformPoint(Vector3dc point) {
        return new Vector3d(point).sub(position).rotate(new Quaterniond(rotation).conjugate());
    }

    public Vector3d inverseTransformDirection(Vector3dc direction) {
        return new Vector3d(direction).rotate(new Quaterniond(rotation).conjugate());
    }

    public Transform3d interpolate(Transform3d target, double alpha) {
        final double t = Math.max(0.0, Math.min(1.0, alpha));
        final Vector3d p = new Vector3d(position).lerp(target.position, t);
        final Quaterniond q = new Quaterniond(rotation).slerp(target.rotation, t);
        return new Transform3d(p, q);
    }

    public boolean isFinite() {
        return isFinite(position) && isFinite(rotation);
    }

    private static boolean isFinite(Vector3dc v) {
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    private static boolean isFinite(Quaterniondc q) {
        return Double.isFinite(q.x()) && Double.isFinite(q.y())
                && Double.isFinite(q.z()) && Double.isFinite(q.w());
    }
}
