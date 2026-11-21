package io.mikaple.crythst.physics.character;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Kinematic character capsule. Uses the world's arbitrary {@code up} vector, so
 * gravity can point sideways or upside down.
 */
public interface CharacterController extends AutoCloseable {
    Transform3d position();

    void teleport(Transform3d transform);

    void setWalkDirection(Vector3dc direction);

    Vector3d walkDirection();

    void setLinearVelocity(Vector3dc velocity);

    Vector3d linearVelocity();

    void jump();

    boolean onGround();

    void setUp(Vector3dc up);

    void setStepHeight(double height);

    void setMaxSlope(double radians);

    void setGravity(Vector3dc gravity);

    void setDamping(double linear, double angular);

    void warp(Vector3dc position);

    boolean closed();

    @Override
    void close();
}
