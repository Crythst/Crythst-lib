package io.mikaple.crythst.physics.query;

import io.mikaple.crythst.physics.body.BodyHandle;
import org.joml.Vector3d;

/**
 * A shape-cast intersection.
 *
 * @param body     the body that was hit
 * @param fraction fraction of the sweep, in [0, 1]
 * @param normal   world-space surface normal
 * @param point    approximate world-space contact point
 * @param distance world-space travel distance
 */
public record SweepHit(BodyHandle body, double fraction, Vector3d normal, Vector3d point, double distance) {
    public SweepHit {
        normal = new Vector3d(normal);
        point = new Vector3d(point);
    }

    @Override
    public Vector3d normal() {
        return new Vector3d(normal);
    }

    @Override
    public Vector3d point() {
        return new Vector3d(point);
    }
}
