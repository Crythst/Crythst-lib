package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;

/**
 * Sphere centred on the local origin.
 */
public record SphereShape(double radius) implements PhysicsShape {
    public SphereShape {
        Validate.positive(radius, "radius");
    }

    @Override
    public ShapeType type() {
        return ShapeType.SPHERE;
    }

    @Override
    public Aabb localBounds() {
        final Vector3d half = new Vector3d(radius, radius, radius);
        return Aabb.fromCenterHalfExtents(new Vector3d(), half);
    }

    @Override
    public double volume() {
        return (4.0 / 3.0) * Math.PI * radius * radius * radius;
    }
}
