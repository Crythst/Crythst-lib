package io.mikaple.crythst.math;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Axis-aligned bounding box in world space.
 */
public record Aabb(Vector3d min, Vector3d max) {
    public Aabb {
        min = new Vector3d(min);
        max = new Vector3d(max);
        if (min.x > max.x || min.y > max.y || min.z > max.z) {
            throw new IllegalArgumentException("AABB min must not exceed max: " + min + " > " + max);
        }
    }

    public static Aabb of(Vector3dc min, Vector3dc max) {
        return new Aabb(new Vector3d(min), new Vector3d(max));
    }

    public static Aabb fromCenterSize(Vector3dc center, Vector3dc size) {
        final Vector3d half = new Vector3d(size).mul(0.5);
        return new Aabb(new Vector3d(center).sub(half), new Vector3d(center).add(half));
    }

    public static Aabb fromCenterHalfExtents(Vector3dc center, Vector3dc halfExtents) {
        return new Aabb(new Vector3d(center).sub(halfExtents), new Vector3d(center).add(halfExtents));
    }

    @Override
    public Vector3d min() {
        return new Vector3d(min);
    }

    @Override
    public Vector3d max() {
        return new Vector3d(max);
    }

    public Vector3d center() {
        return new Vector3d(min).add(max).mul(0.5);
    }

    public Vector3d size() {
        return new Vector3d(max).sub(min);
    }

    public Vector3d halfExtents() {
        return size().mul(0.5);
    }

    public double volume() {
        final Vector3d size = size();
        return size.x * size.y * size.z;
    }

    public boolean contains(Vector3dc point) {
        return point.x() >= min.x && point.x() <= max.x
                && point.y() >= min.y && point.y() <= max.y
                && point.z() >= min.z && point.z() <= max.z;
    }

    public boolean intersects(Aabb other) {
        return max.x >= other.min.x && min.x <= other.max.x
                && max.y >= other.min.y && min.y <= other.max.y
                && max.z >= other.min.z && min.z <= other.max.z;
    }

    public Aabb union(Aabb other) {
        return new Aabb(
                new Vector3d(Math.min(min.x, other.min.x), Math.min(min.y, other.min.y), Math.min(min.z, other.min.z)),
                new Vector3d(Math.max(max.x, other.max.x), Math.max(max.y, other.max.y), Math.max(max.z, other.max.z))
        );
    }

    public Aabb expand(double amount) {
        final Vector3d offset = new Vector3d(amount, amount, amount);
        return new Aabb(
                new Vector3d(min).sub(offset),
                new Vector3d(max).add(offset)
        );
    }

    public Aabb offset(Vector3dc offset) {
        return new Aabb(new Vector3d(min).add(offset), new Vector3d(max).add(offset));
    }

    /**
     * Computes the oriented bounds of this local box after applying a transform.
     */
    public OrientedBox toOrientedBox(Transform3d transform) {
        return OrientedBox.fromAabb(this, transform);
    }

    /**
     * Computes the world-space bounds of this box after applying a transform.
     */
    public Aabb transform(Transform3d transform) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < 8; i++) {
            final Vector3d corner = new Vector3d(
                    (i & 1) == 0 ? min.x : max.x,
                    (i & 2) == 0 ? min.y : max.y,
                    (i & 4) == 0 ? min.z : max.z
            );
            transform.transformPoint(corner);
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }

        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }
}
