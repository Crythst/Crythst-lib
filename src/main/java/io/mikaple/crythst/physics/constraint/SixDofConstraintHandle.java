package io.mikaple.crythst.physics.constraint;

/**
 * Optional capability for full 6-DOF joints.
 *
 * <p>Indices follow Bullet's order:</p>
 * <pre>
 * 0 - X translation   1 - Y translation   2 - Z translation
 * 3 - X rotation      4 - Y rotation      5 - Z rotation
 * </pre>
 *
 * <p>Setting an axis free maps to Bullet's {@code lowerLimit > upperLimit}
 * convention. Limits, target velocities, forces, stiffness and damping use the
 * units of the axis: metres and newtons for translation, radians and
 * newton-metres for rotation.</p>
 */
public interface SixDofConstraintHandle extends ConstraintHandle {
    boolean isAxisFree(int dofIndex);

    void setAxisFree(int dofIndex, boolean free);

    double lowerLimit(int dofIndex);

    double upperLimit(int dofIndex);

    void setLimits(int dofIndex, double lowerLimit, double upperLimit);

    boolean isMotorEnabled(int dofIndex);

    void setMotorEnabled(int dofIndex, boolean enabled);

    double motorTargetVelocity(int dofIndex);

    void setMotorTargetVelocity(int dofIndex, double velocity);

    double maxMotorForce(int dofIndex);

    void setMaxMotorForce(int dofIndex, double maxForce);

    boolean isSpringEnabled(int dofIndex);

    void setSpringEnabled(int dofIndex, boolean enabled);

    double stiffness(int dofIndex);

    void setStiffness(int dofIndex, double stiffness);

    double damping(int dofIndex);

    void setDamping(int dofIndex, double damping);
}
