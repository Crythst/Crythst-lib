package io.mikaple.crythst.physics.body;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import org.joml.Vector3dc;

/**
 * A live rigid body owned by a {@code PhysicsWorld}. All transforms are in
 * world space; implementations handle precision rebasing internally.
 */
public interface BodyHandle {
    long id();

    BodyType type();

    void setType(BodyType type);

    Transform3d transform();

    void setTransform(Transform3d transform);

    void teleport(Transform3d transform);

    Aabb aabb();

    /**
     * Tight, rotation-aware bounds derived from the shape's local AABB and the
     * body's current rotation. Unlike {@link #aabb()} this does not inflate
     * when a structure tilts.
     */
    default io.mikaple.crythst.math.OrientedBox obb() {
        return io.mikaple.crythst.math.OrientedBox.fromAabb(shape().localBounds(), transform());
    }

    double mass();

    void setMass(double mass);

    PhysicsShape shape();

    /**
     * Replaces collision geometry while preserving the current pose and
     * velocity. Used when a block structure is edited.
     */
    void setShape(PhysicsShape shape, double mass);

    PhysicsMaterial material();

    void setMaterial(PhysicsMaterial material);

    void setGravityScale(double scale);

    double gravityScale();

    /**
     * Overrides world gravity for this body. The vector is an acceleration in
     * m/s²; any direction is allowed.
     */
    void setGravityOverride(Vector3dc gravity);

    void clearGravityOverride();

    /**
     * @return the current per-body override, or {@code null}
     */
    org.joml.Vector3d gravityOverride();

    void setDamping(double linear, double angular);

    void setLinearVelocity(Vector3dc velocity);

    void setAngularVelocity(Vector3dc velocity);

    org.joml.Vector3d linearVelocity();

    org.joml.Vector3d angularVelocity();

    void applyCentralForce(Vector3dc force);

    void applyCentralImpulse(Vector3dc impulse);

    void applyImpulseAtPoint(Vector3dc impulse, Vector3dc worldPoint);

    void applyTorque(Vector3dc torque);

    void applyTorqueImpulse(Vector3dc impulse);

    void clearForces();

    boolean active();

    void activate();

    void setSleepingThresholds(double linear, double angular);

    void setLinearFactor(Vector3dc factor);

    void setAngularFactor(Vector3dc factor);

    void setCcd(boolean enabled, double sweptSphereRadius);

    boolean ccd();

    boolean removeRequested();

    void requestRemove();

    boolean inWorld();

    Object userData();

    void setUserData(Object userData);

    BodySnapshot snapshot();
}
