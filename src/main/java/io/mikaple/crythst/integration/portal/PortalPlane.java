package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The oriented rectangular surface of a portal.
 *
 * <p>The plane stores a right-handed basis {@code (right, up, normal)}:
 * {@code normal = right x up}. The basis therefore defines a local coordinate
 * system in metres:</p>
 *
 * <ul>
 *     <li>{@code x} runs along {@link #right()},</li>
 *     <li>{@code y} runs along {@link #up()},</li>
 *     <li>{@code z} runs along {@link #normal()}.</li>
 * </ul>
 *
 * <p>For a portal created by a player, {@code normal} points <em>towards</em>
 * the creator (it is the opposite of the look direction), so the visible front
 * face is the {@code +normal} side. The rectangle is centred on
 * {@link #origin()}.</p>
 *
 * <p>This class is intentionally free of Minecraft types so both the server
 * model and the client renderer can share it.</p>
 */
public final class PortalPlane {
    /** Maximum angle tolerance for a usable derived basis. */
    private static final double MIN_LENGTH_SQUARED = 1.0e-12;

    private final Vector3d origin;
    private final Vector3d normal;
    private final Vector3d right;
    private final Vector3d up;
    private final double width;
    private final double height;

    public PortalPlane(Vector3dc origin, Vector3dc normal, Vector3dc right,
                       double width, double height) {
        if (origin == null || normal == null || right == null) {
            throw new IllegalArgumentException("Portal plane vectors must not be null");
        }
        if (!(width > 0.0) || !(height > 0.0)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Portal size must be positive and finite: "
                    + width + " x " + height);
        }

        final Vector3d n = new Vector3d(normal);
        if (n.lengthSquared() < MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException("Portal normal must not be zero");
        }
        n.normalize();

        final Vector3d r = new Vector3d(right);
        // Remove any component along the normal and re-orthonormalise.
        r.fma(-r.dot(n), n);
        if (r.lengthSquared() < MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException("Portal right vector is parallel to the normal");
        }
        r.normalize();
        final Vector3d u = new Vector3d(n).cross(r).normalize();

        this.origin = new Vector3d(origin);
        this.normal = n;
        this.right = r;
        this.up = u;
        this.width = width;
        this.height = height;
    }

    /**
     * Derives a portal plane from a creator's eye position and look direction.
     *
     * <p>The plane is placed {@code distance} blocks along the look direction
     * and faces back towards the creator. If the look direction is (nearly)
     * vertical, a stable fallback up axis is chosen so the basis stays
     * well-defined.</p>
     */
    public static PortalPlane fromLook(Vector3dc eye, Vector3dc look,
                                       double distance, double width, double height) {
        final Vector3d forward = new Vector3d(look);
        if (forward.lengthSquared() < MIN_LENGTH_SQUARED) {
            forward.set(0.0, 0.0, -1.0);
        } else {
            forward.normalize();
        }

        // The visible face points back at the creator.
        final Vector3d n = new Vector3d(forward).negate();

        final Vector3d referenceUp = new Vector3d(0.0, 1.0, 0.0);
        if (Math.abs(n.dot(referenceUp)) > 0.999) {
            referenceUp.set(0.0, 0.0, 1.0);
        }

        // right = up x normal, up = normal x right, so normal = right x up.
        final Vector3d r = new Vector3d(referenceUp).cross(n).normalize();
        final Vector3d u = new Vector3d(n).cross(r).normalize();

        final Vector3d centre = new Vector3d(eye).fma(distance, forward);
        return new PortalPlane(centre, n, r, width, height);
    }

    public Vector3d origin() {
        return new Vector3d(origin);
    }

    public Vector3d normal() {
        return new Vector3d(normal);
    }

    public Vector3d right() {
        return new Vector3d(right);
    }

    public Vector3d up() {
        return new Vector3d(up);
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    /** Converts a world-space point into plane-local coordinates. */
    public Vector3d local(Vector3dc world, Vector3d dest) {
        final double x = world.x() - origin.x;
        final double y = world.y() - origin.y;
        final double z = world.z() - origin.z;
        return dest.set(
                x * right.x + y * right.y + z * right.z,
                x * up.x + y * up.y + z * up.z,
                x * normal.x + y * normal.y + z * normal.z
        );
    }

    public Vector3d local(Vector3dc world) {
        return local(world, new Vector3d());
    }

    /** Converts plane-local coordinates into a world-space point. */
    public Vector3d worldPoint(Vector3dc local, Vector3d dest) {
        return dest.set(origin)
                .fma(local.x(), right)
                .fma(local.y(), up)
                .fma(local.z(), normal);
    }

    public Vector3d worldPoint(Vector3dc local) {
        return worldPoint(local, new Vector3d());
    }

    /** Signed distance from a world point to the plane (along the normal). */
    public double signedDistance(Vector3dc point) {
        return new Vector3d(point).sub(origin).dot(normal);
    }

    public double distanceSquared(Vector3dc point) {
        return new Vector3d(point).distanceSquared(origin);
    }

    /**
     * True when a world point lies within the rectangle and within
     * {@code thickness} metres of the plane. This is the portal "trigger
     * volume".
     */
    public boolean contains(Vector3dc point, double thickness) {
        final Vector3d local = local(point);
        return Math.abs(local.z) <= thickness
                && Math.abs(local.x) <= width * 0.5
                && Math.abs(local.y) <= height * 0.5;
    }

    /**
     * Conservative test of an oriented box against the portal trigger volume.
     * Useful for broad-phase checks and debug tooling; the v1 teleporter uses
     * the stricter centre test in {@link #contains} so a large structure only
     * traverses when its centre crosses the plane.
     */
    public boolean intersects(OrientedBox box, double thickness) {
        final Vector3d rel = new Vector3d(box.center()).sub(origin);
        final Vector3d[] axes = box.axes();
        final Vector3d half = box.halfExtents();

        final double extentNormal = projectedExtent(axes, half, normal);
        if (Math.abs(rel.dot(normal)) > thickness + extentNormal) {
            return false;
        }
        final double extentRight = projectedExtent(axes, half, right);
        if (Math.abs(rel.dot(right)) > width * 0.5 + extentRight) {
            return false;
        }
        final double extentUp = projectedExtent(axes, half, up);
        return Math.abs(rel.dot(up)) <= height * 0.5 + extentUp;
    }

    private static double projectedExtent(Vector3d[] axes, Vector3dc half, Vector3dc axis) {
        double extent = 0.0;
        for (int i = 0; i < 3; i++) {
            extent += Math.abs(axes[i].dot(axis)) * half.get(i);
        }
        return extent;
    }

    /**
     * The four corners in counter-clockwise order when viewed from the
     * {@code +normal} side: bottom-left, bottom-right, top-right, top-left.
     */
    public Vector3d[] corners() {
        final Vector3d halfRight = new Vector3d(right).mul(width * 0.5);
        final Vector3d halfUp = new Vector3d(up).mul(height * 0.5);
        final Vector3d bottomLeft = new Vector3d(origin).sub(halfRight).sub(halfUp);
        final Vector3d bottomRight = new Vector3d(origin).add(halfRight).sub(halfUp);
        final Vector3d topRight = new Vector3d(origin).add(halfRight).add(halfUp);
        final Vector3d topLeft = new Vector3d(origin).sub(halfRight).add(halfUp);
        return new Vector3d[]{bottomLeft, bottomRight, topRight, topLeft};
    }

    /** Axis-aligned bounds of the rectangle. */
    public Aabb bounds() {
        final Vector3d[] corners = corners();
        final Vector3d min = new Vector3d(corners[0]);
        final Vector3d max = new Vector3d(corners[0]);
        for (int i = 1; i < corners.length; i++) {
            min.min(corners[i]);
            max.max(corners[i]);
        }
        return new Aabb(min, max);
    }

    @Override
    public String toString() {
        return String.format("PortalPlane[origin=(%.2f, %.2f, %.2f) normal=(%.2f, %.2f, %.2f) %.1fx%.1f]",
                origin.x, origin.y, origin.z, normal.x, normal.y, normal.z, width, height);
    }
}
