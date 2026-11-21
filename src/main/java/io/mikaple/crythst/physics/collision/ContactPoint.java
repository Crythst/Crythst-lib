package io.mikaple.crythst.physics.collision;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One contact manifold point.
 *
 * @param position world-space contact position
 * @param normal   world-space normal pointing from body B towards body A
 * @param impulse  applied normal impulse from the previous step
 * @param distance penetration depth (negative when overlapping)
 */
public record ContactPoint(Vector3d position, Vector3d normal, double impulse, double distance) {
    public ContactPoint {
        position = new Vector3d(position);
        normal = new Vector3d(normal);
    }

    @Override
    public Vector3d position() {
        return new Vector3d(position);
    }

    @Override
    public Vector3d normal() {
        return new Vector3d(normal);
    }

    public Vector3d position(Vector3d dest) {
        return dest.set(position);
    }

    public Vector3d normal(Vector3d dest) {
        return dest.set(normal);
    }

    public double depth() {
        return -distance;
    }
}
