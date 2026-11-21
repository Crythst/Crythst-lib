package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;

/**
 * Cylinder centred on the local origin.
 *
 * @param radius radius
 * @param height height of the cylinder
 * @param axis   axis along which the cylinder extends
 */
public record CylinderShape(double radius, double height, Axis axis) implements PhysicsShape {
    public CylinderShape {
        Validate.positive(radius, "radius");
        Validate.positive(height, "height");
        axis = axis == null ? Axis.Y : axis;
    }

    @Override
    public ShapeType type() {
        return ShapeType.CYLINDER;
    }

    @Override
    public Aabb localBounds() {
        final double halfHeight = height * 0.5;
        final Vector3d half = switch (axis) {
            case X -> new Vector3d(halfHeight, radius, radius);
            case Y -> new Vector3d(radius, halfHeight, radius);
            case Z -> new Vector3d(radius, radius, halfHeight);
        };
        return Aabb.fromCenterHalfExtents(new Vector3d(), half);
    }

    @Override
    public double volume() {
        return Math.PI * radius * radius * height;
    }
}
