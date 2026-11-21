package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Convex hull built from a point cloud. At least four non-coplanar points are
 * required by Bullet.
 */
public final class ConvexHullShape implements PhysicsShape {
    private final List<Vector3d> points;

    public ConvexHullShape(List<? extends Vector3dc> points) {
        Validate.notNull(points, "points");
        if (points.size() < 4) {
            throw new IllegalArgumentException("A convex hull needs at least 4 points");
        }
        final List<Vector3d> copy = new ArrayList<>(points.size());
        for (Vector3dc point : points) {
            copy.add(new Vector3d(Validate.notNull(point, "point")));
        }
        this.points = List.copyOf(copy);
    }

    public List<Vector3d> points() {
        final List<Vector3d> copy = new ArrayList<>(points.size());
        for (Vector3d point : points) {
            copy.add(new Vector3d(point));
        }
        return copy;
    }

    @Override
    public ShapeType type() {
        return ShapeType.CONVEX_HULL;
    }

    @Override
    public Aabb localBounds() {
        final Vector3d min = new Vector3d(Double.POSITIVE_INFINITY);
        final Vector3d max = new Vector3d(Double.NEGATIVE_INFINITY);
        for (Vector3dc point : points) {
            min.min(point);
            max.max(point);
        }
        return Aabb.of(min, max);
    }

    @Override
    public double volume() {
        // A general hull volume is not needed by the library; callers that need
        // mass must specify it explicitly on BodyDef.
        return 0.0;
    }
}
