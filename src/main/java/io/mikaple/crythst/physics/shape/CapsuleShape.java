package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;

/**
 * Capsule centred on the local origin.
 *
 * @param radius            radius of the caps
 * @param cylinderHeight    height of the cylindrical section (not including the
 *                          two hemispherical caps), matching Bullet's convention
 * @param axis              axis along which the capsule extends
 */
public record CapsuleShape(double radius, double cylinderHeight, Axis axis) implements PhysicsShape {
    public CapsuleShape {
        Validate.positive(radius, "radius");
        Validate.nonNegative(cylinderHeight, "cylinderHeight");
        axis = axis == null ? Axis.Y : axis;
    }

    public static CapsuleShape standing(double radius, double totalHeight) {
        Validate.positive(totalHeight, "totalHeight");
        return new CapsuleShape(radius, Math.max(0.0, totalHeight - 2.0 * radius), Axis.Y);
    }

    @Override
    public ShapeType type() {
        return ShapeType.CAPSULE;
    }

    @Override
    public Aabb localBounds() {
        final double halfLength = cylinderHeight * 0.5 + radius;
        final Vector3d half = switch (axis) {
            case X -> new Vector3d(halfLength, radius, radius);
            case Y -> new Vector3d(radius, halfLength, radius);
            case Z -> new Vector3d(radius, radius, halfLength);
        };
        return Aabb.fromCenterHalfExtents(new Vector3d(), half);
    }

    @Override
    public double volume() {
        return Math.PI * radius * radius * cylinderHeight
                + (4.0 / 3.0) * Math.PI * radius * radius * radius;
    }

    public double totalHeight() {
        return cylinderHeight + 2.0 * radius;
    }
}
