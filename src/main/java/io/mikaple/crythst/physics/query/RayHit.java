package io.mikaple.crythst.physics.query;

import io.mikaple.crythst.physics.body.BodyHandle;
import org.joml.Vector3d;

/**
 * A ray intersection.
 *
 * @param body     the body that was hit
 * @param fraction fraction of the query segment, in [0, 1]
 * @param point    world-space hit point
 * @param normal   world-space surface normal
 * @param distance world-space distance from the ray origin
 */
public record RayHit(BodyHandle body, double fraction, Vector3d point, Vector3d normal, double distance) {
    public RayHit {
        point = new Vector3d(point);
        normal = new Vector3d(normal);
    }

    @Override
    public Vector3d point() {
        return new Vector3d(point);
    }

    @Override
    public Vector3d normal() {
        return new Vector3d(normal);
    }
}
