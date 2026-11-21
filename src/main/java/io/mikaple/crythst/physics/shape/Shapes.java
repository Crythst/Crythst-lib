package io.mikaple.crythst.physics.shape;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Convenience factory methods for shapes.
 */
public final class Shapes {
    private Shapes() {
    }

    public static BoxShape box(double sizeX, double sizeY, double sizeZ) {
        return BoxShape.of(sizeX, sizeY, sizeZ);
    }

    public static BoxShape cube(double size) {
        return BoxShape.cube(size);
    }

    public static BoxShape halfExtents(Vector3dc halfExtents) {
        return new BoxShape(new Vector3d(halfExtents));
    }

    public static SphereShape sphere(double radius) {
        return new SphereShape(radius);
    }

    public static CapsuleShape capsule(double radius, double totalHeight) {
        return CapsuleShape.standing(radius, totalHeight);
    }

    public static CylinderShape cylinder(double radius, double height, Axis axis) {
        return new CylinderShape(radius, height, axis);
    }

    public static PlaneShape plane(Vector3dc normal, double constant) {
        return new PlaneShape(new Vector3d(normal), constant);
    }
}
