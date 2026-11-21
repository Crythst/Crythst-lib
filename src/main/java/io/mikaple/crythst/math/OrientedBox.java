package io.mikaple.crythst.math;

import io.mikaple.crythst.util.Validate;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Oriented (tilted) bounding box: centre, half extents and a rotation.
 *
 * <p>A normal {@link Aabb} cannot follow a tumbling structure. Crythst exposes
 * an OBB for every body and uses a 15-axis SAT test for OBB/OBB and OBB/AABB
 * overlap, so tilted structures get tight, rotation-aware bounds.</p>
 */
public record OrientedBox(Vector3d center, Vector3d halfExtents, Quaterniond rotation) {
    private static final double EPSILON = 1.0e-9;

    public OrientedBox {
        center = new Vector3d(Validate.notNull(center, "center"));
        halfExtents = new Vector3d(Validate.notNull(halfExtents, "halfExtents"));
        // Zero is legal: an Aabb may be flat (a plane/slab) or empty, and
        // fromAabb must round-trip it. Only negative extents are invalid.
        // Rejecting zero used to make OrientedBox.fromAabb throw for a legal
        // Aabb, which took collision and render broad phases down instead of
        // degrading to a point/segment/plane.
        Validate.nonNegative(halfExtents.x, "halfExtents.x");
        Validate.nonNegative(halfExtents.y, "halfExtents.y");
        Validate.nonNegative(halfExtents.z, "halfExtents.z");
        rotation = new Quaterniond(Validate.notNull(rotation, "rotation"));
        if (rotation.lengthSquared() < 1.0e-20) {
            rotation.identity();
        } else {
            rotation.normalize();
        }
    }

    public static OrientedBox fromAabb(Aabb aabb) {
        return new OrientedBox(aabb.center(), aabb.halfExtents(), new Quaterniond());
    }

    /**
     * Builds the OBB of a local AABB after applying a rigid transform.
     */
    public static OrientedBox fromAabb(Aabb localBounds, Transform3d transform) {
        return new OrientedBox(
                transform.transformPoint(localBounds.center()),
                localBounds.halfExtents(),
                transform.rotation()
        );
    }

    @Override
    public Vector3d center() {
        return new Vector3d(center);
    }

    @Override
    public Vector3d halfExtents() {
        return new Vector3d(halfExtents);
    }

    @Override
    public Quaterniond rotation() {
        return new Quaterniond(rotation);
    }

    public double volume() {
        return 8.0 * halfExtents.x * halfExtents.y * halfExtents.z;
    }

    public Vector3d size() {
        return new Vector3d(halfExtents).mul(2.0);
    }

    public Vector3d[] axes() {
        // JOML stores rotation matrices column-major: m00,m01,m02 are the
        // world image of local +X, m10,m11,m12 of local +Y and m20,m21,m22 of
        // local +Z. Taking (m00,m10,m20) here would return the transpose (the
        // inverse rotation), which makes toAabb/corners/SAT wrong for general
        // orientations.
        final Matrix3d matrix = new Matrix3d().rotation(rotation);
        return new Vector3d[]{
                new Vector3d(matrix.m00, matrix.m01, matrix.m02),
                new Vector3d(matrix.m10, matrix.m11, matrix.m12),
                new Vector3d(matrix.m20, matrix.m21, matrix.m22)
        };
    }

    public List<Vector3d> corners() {
        final Vector3d[] axes = axes();
        final List<Vector3d> corners = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            final Vector3d corner = new Vector3d(center);
            corner.add(new Vector3d(axes[0]).mul((i & 1) == 0 ? halfExtents.x : -halfExtents.x));
            corner.add(new Vector3d(axes[1]).mul((i & 2) == 0 ? halfExtents.y : -halfExtents.y));
            corner.add(new Vector3d(axes[2]).mul((i & 4) == 0 ? halfExtents.z : -halfExtents.z));
            corners.add(corner);
        }
        return corners;
    }

    public Aabb toAabb() {
        final double minX = Double.POSITIVE_INFINITY;
        final double minY = Double.POSITIVE_INFINITY;
        final double minZ = Double.POSITIVE_INFINITY;
        final double maxX = Double.NEGATIVE_INFINITY;
        final double maxY = Double.NEGATIVE_INFINITY;
        final double maxZ = Double.NEGATIVE_INFINITY;

        // The extent along a world axis is the sum of the projected half extents.
        final Vector3d[] axes = axes();
        final double ex = Math.abs(axes[0].x) * halfExtents.x
                + Math.abs(axes[1].x) * halfExtents.y
                + Math.abs(axes[2].x) * halfExtents.z;
        final double ey = Math.abs(axes[0].y) * halfExtents.x
                + Math.abs(axes[1].y) * halfExtents.y
                + Math.abs(axes[2].y) * halfExtents.z;
        final double ez = Math.abs(axes[0].z) * halfExtents.x
                + Math.abs(axes[1].z) * halfExtents.y
                + Math.abs(axes[2].z) * halfExtents.z;
        return new Aabb(
                new Vector3d(center.x - ex, center.y - ey, center.z - ez),
                new Vector3d(center.x + ex, center.y + ey, center.z + ez)
        );
    }

    public boolean contains(Vector3dc point) {
        final Vector3d local = new Vector3d(point).sub(center).rotate(new Quaterniond(rotation).conjugate());
        return Math.abs(local.x) <= halfExtents.x + EPSILON
                && Math.abs(local.y) <= halfExtents.y + EPSILON
                && Math.abs(local.z) <= halfExtents.z + EPSILON;
    }

    public OrientedBox transform(Transform3d transform) {
        return new OrientedBox(
                transform.transformPoint(center),
                halfExtents,
                new Quaterniond(transform.rotation()).mul(rotation)
        );
    }

    public OrientedBox expand(double amount) {
        return new OrientedBox(center, new Vector3d(halfExtents).add(amount, amount, amount), rotation);
    }

    /** Closest point on or inside the box. */
    public Vector3d closestPoint(Vector3dc point) {
        final Vector3d local = new Vector3d(point).sub(center).rotate(new Quaterniond(rotation).conjugate());
        local.x = Math.max(-halfExtents.x, Math.min(halfExtents.x, local.x));
        local.y = Math.max(-halfExtents.y, Math.min(halfExtents.y, local.y));
        local.z = Math.max(-halfExtents.z, Math.min(halfExtents.z, local.z));
        return local.rotate(rotation).add(center);
    }

    public boolean intersects(Aabb aabb) {
        return intersects(fromAabb(aabb));
    }

    /**
     * Separating-axis test for two oriented boxes.
     */
    public boolean intersects(OrientedBox other) {
        final Vector3d[] a = axes();
        final Vector3d[] b = other.axes();
        final Vector3d tWorld = new Vector3d(other.center).sub(center);

        final double[] t = new double[]{
                tWorld.dot(a[0]), tWorld.dot(a[1]), tWorld.dot(a[2])
        };
        final double[][] r = new double[3][3];
        final double[][] absR = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i].dot(b[j]);
                absR[i][j] = Math.abs(r[i][j]) + EPSILON;
            }
        }

        final double ax = halfExtents.x;
        final double ay = halfExtents.y;
        final double az = halfExtents.z;
        final double bx = other.halfExtents.x;
        final double by = other.halfExtents.y;
        final double bz = other.halfExtents.z;

        // A's face axes.
        if (Math.abs(t[0]) > ax + bx * absR[0][0] + by * absR[0][1] + bz * absR[0][2]) {
            return false;
        }
        if (Math.abs(t[1]) > ay + bx * absR[1][0] + by * absR[1][1] + bz * absR[1][2]) {
            return false;
        }
        if (Math.abs(t[2]) > az + bx * absR[2][0] + by * absR[2][1] + bz * absR[2][2]) {
            return false;
        }

        // B's face axes.
        if (Math.abs(tWorld.dot(b[0])) > ax * absR[0][0] + ay * absR[1][0] + az * absR[2][0] + bx) {
            return false;
        }
        if (Math.abs(tWorld.dot(b[1])) > ax * absR[0][1] + ay * absR[1][1] + az * absR[2][1] + by) {
            return false;
        }
        if (Math.abs(tWorld.dot(b[2])) > ax * absR[0][2] + ay * absR[1][2] + az * absR[2][2] + bz) {
            return false;
        }

        // Nine cross axes.
        final double[] aHalf = {ax, ay, az};
        final double[] bHalf = {bx, by, bz};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final int i1 = (i + 1) % 3;
                final int i2 = (i + 2) % 3;
                final int j1 = (j + 1) % 3;
                final int j2 = (j + 2) % 3;
                final double ra = aHalf[i1] * absR[i2][j] + aHalf[i2] * absR[i1][j];
                final double rb = bHalf[j1] * absR[i][j2] + bHalf[j2] * absR[i][j1];
                final double projection = Math.abs(t[i2] * r[i1][j] - t[i1] * r[i2][j]);
                if (projection > ra + rb) {
                    return false;
                }
            }
        }
        return true;
    }
}
