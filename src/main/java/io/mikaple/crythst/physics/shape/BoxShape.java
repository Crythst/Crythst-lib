package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Axis-aligned box centred on the local origin.
 */
public record BoxShape(Vector3d halfExtents) implements PhysicsShape {
    public BoxShape {
        halfExtents = new Vector3d(halfExtents);
        Validate.positive(halfExtents.x, "halfExtents.x");
        Validate.positive(halfExtents.y, "halfExtents.y");
        Validate.positive(halfExtents.z, "halfExtents.z");
    }

    public static BoxShape cube(double size) {
        final double half = Validate.positive(size, "size") * 0.5;
        return new BoxShape(new Vector3d(half, half, half));
    }

    public static BoxShape of(double sizeX, double sizeY, double sizeZ) {
        Validate.positive(sizeX, "sizeX");
        Validate.positive(sizeY, "sizeY");
        Validate.positive(sizeZ, "sizeZ");
        return new BoxShape(new Vector3d(sizeX * 0.5, sizeY * 0.5, sizeZ * 0.5));
    }

    @Override
    public Vector3d halfExtents() {
        return new Vector3d(halfExtents);
    }

    @Override
    public ShapeType type() {
        return ShapeType.BOX;
    }

    @Override
    public Aabb localBounds() {
        return Aabb.fromCenterHalfExtents(new Vector3d(), halfExtents);
    }

    @Override
    public double volume() {
        return 8.0 * halfExtents.x * halfExtents.y * halfExtents.z;
    }

    public Vector3d size(Vector3d dest) {
        return dest.set(halfExtents).mul(2.0);
    }

    @Override
    public String toString() {
        return "BoxShape" + halfExtents;
    }
}
