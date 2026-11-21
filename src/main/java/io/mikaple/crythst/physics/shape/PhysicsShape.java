package io.mikaple.crythst.physics.shape;

import io.mikaple.crythst.math.Aabb;

/**
 * Backend-independent collision shape description, expressed in body-local
 * coordinates.
 */
public interface PhysicsShape {
    ShapeType type();

    /**
     * Bounds around the shape's local origin.
     */
    Aabb localBounds();

    /**
     * Volume in cubic metres, or {@code 0} when the shape cannot compute one
     * cheaply (for example an arbitrary convex hull).
     */
    double volume();
}
