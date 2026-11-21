package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.CrythstMath;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Infinite plane. Useful for simple test worlds and terrain stand-ins.
 */
public record PlaneShape(Vector3d normal, double constant) implements PhysicsShape {
    public PlaneShape {
        normal = new Vector3d(Validate.notNull(normal, "normal"));
        CrythstMath.safeNormalize(normal, normal);
        Validate.finite(constant, "constant");
    }

    public static PlaneShape horizontal(double y) {
        return new PlaneShape(new Vector3d(0.0, 1.0, 0.0), y);
    }

    @Override
    public Vector3d normal() {
        return new Vector3d(normal);
    }

    @Override
    public ShapeType type() {
        return ShapeType.PLANE;
    }

    @Override
    public Aabb localBounds() {
        final double extent = 1.0e6;
        return new Aabb(new Vector3d(-extent, -extent, -extent), new Vector3d(extent, extent, extent));
    }

    @Override
    public double volume() {
        return 0.0;
    }

    /** Signed distance from a point to the plane. */
    public double distance(Vector3dc point) {
        return normal.dot(point) + constant;
    }
}
