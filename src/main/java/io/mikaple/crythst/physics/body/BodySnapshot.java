package io.mikaple.crythst.physics.body;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;

import org.joml.Vector3d;

/**
 * Immutable point-in-time view of a body, safe to hand to rendering or
 * networking code.
 */
public record BodySnapshot(
        long id,
        BodyType type,
        Transform3d transform,
        Vector3d linearVelocity,
        Vector3d angularVelocity,
        double mass,
        boolean active,
        Aabb aabb
) {
    public BodySnapshot {
        linearVelocity = new Vector3d(linearVelocity);
        angularVelocity = new Vector3d(angularVelocity);
    }

    @Override
    public Vector3d linearVelocity() {
        return new Vector3d(linearVelocity);
    }

    @Override
    public Vector3d angularVelocity() {
        return new Vector3d(angularVelocity);
    }
}
