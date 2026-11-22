package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import org.joml.Vector2d;
import org.joml.Vector2dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The traversable opening of a portal in the portal plane's local coordinates.
 *
 * <p>A shape is a convex polygon on a {@link PortalPlane}. The rectangle used by
 * {@code /crythst portal create} is represented by the four corner points; a
 * future frame/block-based portal can provide any convex outline without
 * changing traversal or rendering. Points are ordered counter-clockwise when
 * viewed from the {@code +normal} side.</p>
 *
 * <p>The outline is expressed in metres in the same local frame as
 * {@link PortalPlane#local(Vector3dc)}: {@code x} along {@code right},
 * {@code y} along {@code up}, {@code z} along {@code normal}.</p>
 */
public final class PortalShape {
    private static final double EDGE_EPSILON = 1.0e-7;
    private static final double MIN_AREA = 1.0e-9;
    private static final int MAX_OUTLINE_POINTS = 64;

    private final PortalPlane plane;
    private final List<Vector2d> outline;
    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;

    private PortalShape(PortalPlane plane, List<Vector2d> outline) {
        this.plane = Objects.requireNonNull(plane, "plane");
        if (outline.size() < 3 || outline.size() > MAX_OUTLINE_POINTS) {
            throw new IllegalArgumentException("Portal outline needs 3.."
                    + MAX_OUTLINE_POINTS + " points: " + outline.size());
        }
        double localMinX = Double.POSITIVE_INFINITY;
        double localMinY = Double.POSITIVE_INFINITY;
        double localMaxX = Double.NEGATIVE_INFINITY;
        double localMaxY = Double.NEGATIVE_INFINITY;
        final List<Vector2d> copy = new ArrayList<>(outline.size());
        for (Vector2dc point : outline) {
            if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
                throw new IllegalArgumentException("Portal outline point must be finite");
            }
            final Vector2d stored = new Vector2d(point);
            copy.add(stored);
            localMinX = Math.min(localMinX, stored.x);
            localMinY = Math.min(localMinY, stored.y);
            localMaxX = Math.max(localMaxX, stored.x);
            localMaxY = Math.max(localMaxY, stored.y);
        }
        if (Math.abs(signedArea(copy)) < MIN_AREA) {
            throw new IllegalArgumentException("Portal outline is degenerate");
        }
        // Keep a stable winding so triangulation and edge tests agree.
        if (signedArea(copy) < 0.0) {
            java.util.Collections.reverse(copy);
        }
        this.outline = List.copyOf(copy);
        this.minX = localMinX;
        this.minY = localMinY;
        this.maxX = localMaxX;
        this.maxY = localMaxY;
    }

    /** The rectangle defined by the plane's width and height. */
    public static PortalShape rectangle(PortalPlane plane) {
        Objects.requireNonNull(plane, "plane");
        final double halfWidth = plane.width() * 0.5;
        final double halfHeight = plane.height() * 0.5;
        return new PortalShape(plane, List.of(
                new Vector2d(-halfWidth, -halfHeight),
                new Vector2d(halfWidth, -halfHeight),
                new Vector2d(halfWidth, halfHeight),
                new Vector2d(-halfWidth, halfHeight)));
    }

    /** A custom convex outline in the plane's local {@code (right, up)} frame. */
    public static PortalShape polygon(PortalPlane plane, List<? extends Vector2dc> outline) {
        Objects.requireNonNull(outline, "outline");
        final List<Vector2d> points = new ArrayList<>(outline.size());
        for (Vector2dc point : outline) {
            points.add(new Vector2d(point));
        }
        return new PortalShape(plane, points);
    }

    public PortalPlane plane() {
        return plane;
    }

    public List<Vector2d> outline() {
        final List<Vector2d> copy = new ArrayList<>(outline.size());
        for (Vector2d point : outline) {
            copy.add(new Vector2d(point));
        }
        return List.copyOf(copy);
    }

    public boolean rectangular() {
        if (outline.size() != 4) {
            return false;
        }
        final double halfWidth = plane.width() * 0.5;
        final double halfHeight = plane.height() * 0.5;
        final double[][] expected = {
                {-halfWidth, -halfHeight}, {halfWidth, -halfHeight},
                {halfWidth, halfHeight}, {-halfWidth, halfHeight}};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(outline.get(i).x - expected[i][0]) > EDGE_EPSILON
                    || Math.abs(outline.get(i).y - expected[i][1]) > EDGE_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /** Point-in-polygon test in local metres, including a small border slack. */
    public boolean containsLocal(double x, double y) {
        if (x < minX - EDGE_EPSILON || x > maxX + EDGE_EPSILON
                || y < minY - EDGE_EPSILON || y > maxY + EDGE_EPSILON) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            final Vector2d a = outline.get(j);
            final Vector2d b = outline.get(i);
            if (onSegment(a, b, x, y)) {
                return true;
            }
            final boolean crosses = (b.y > y) != (a.y > y)
                    && x < (a.x - b.x) * (y - b.y) / (a.y - b.y) + b.x;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    public boolean contains(Vector3dc worldPoint, double thickness) {
        final Vector3d local = plane.local(worldPoint);
        return Math.abs(local.z) <= thickness && containsLocal(local.x, local.y);
    }

    /** Conservative broad-phase test of an oriented box against the shape. */
    public boolean intersects(OrientedBox box, double thickness) {
        if (box == null) {
            return false;
        }
        final Vector3d centre = box.center();
        final Vector3d local = plane.local(centre);
        if (Math.abs(local.z) > thickness + projectedExtent(box, plane.normal())) {
            return false;
        }
        final double extentX = projectedExtent(box, plane.right());
        final double extentY = projectedExtent(box, plane.up());
        if (local.x < minX - extentX || local.x > maxX + extentX
                || local.y < minY - extentY || local.y > maxY + extentY) {
            return false;
        }
        // For convex shapes, the local centre inside the extruded bounds is a
        // safe approximation for the portal trigger volume.
        return containsLocal(local.x, local.y)
                || containsLocal(local.x - extentX, local.y)
                || containsLocal(local.x + extentX, local.y)
                || containsLocal(local.x, local.y - extentY)
                || containsLocal(local.x, local.y + extentY);
    }

    /** World-space outline vertices in counter-clockwise order. */
    public List<Vector3d> worldOutline() {
        final List<Vector3d> result = new ArrayList<>(outline.size());
        for (Vector2d point : outline) {
            result.add(plane.worldPoint(new Vector3d(point.x, point.y, 0.0)));
        }
        return List.copyOf(result);
    }

    /** Axis-aligned bounds of the opening. */
    public Aabb bounds() {
        final List<Vector3d> vertices = worldOutline();
        final Vector3d min = new Vector3d(vertices.getFirst());
        final Vector3d max = new Vector3d(vertices.getFirst());
        for (int i = 1; i < vertices.size(); i++) {
            min.min(vertices.get(i));
            max.max(vertices.get(i));
        }
        return new Aabb(min, max);
    }

    /** Same shape on a moved plane (for moving portals). */
    public PortalShape withPlane(PortalPlane replacement) {
        return new PortalShape(replacement, outline);
    }

    private static double projectedExtent(OrientedBox box, Vector3dc axis) {
        final Vector3d[] axes = box.axes();
        final Vector3d half = box.halfExtents();
        double extent = 0.0;
        for (int i = 0; i < 3; i++) {
            extent += Math.abs(axes[i].dot(axis)) * half.get(i);
        }
        return extent;
    }

    private static boolean onSegment(Vector2d a, Vector2d b, double x, double y) {
        final double cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
        if (Math.abs(cross) > EDGE_EPSILON) {
            return false;
        }
        return x >= Math.min(a.x, b.x) - EDGE_EPSILON
                && x <= Math.max(a.x, b.x) + EDGE_EPSILON
                && y >= Math.min(a.y, b.y) - EDGE_EPSILON
                && y <= Math.max(a.y, b.y) + EDGE_EPSILON;
    }

    private static double signedArea(List<Vector2d> points) {
        double area = 0.0;
        for (int i = 0; i < points.size(); i++) {
            final Vector2d a = points.get(i);
            final Vector2d b = points.get((i + 1) % points.size());
            area += a.x * b.y - b.x * a.y;
        }
        return area * 0.5;
    }

    @Override
    public String toString() {
        return "PortalShape[" + plane + ", " + outline.size() + " points]";
    }
}
