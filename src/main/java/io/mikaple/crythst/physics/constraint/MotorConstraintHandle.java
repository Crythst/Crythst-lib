package io.mikaple.crythst.physics.constraint;

/**
 * Optional capability for joints that expose a runtime motor.
 *
 * <p>Only {@link ConstraintType#HINGE} joints currently support these methods.
 * Callers should check {@code constraint instanceof MotorConstraintHandle} and
 * the joint type before using them; a backend that does not implement this
 * interface has no runtime motor control.</p>
 *
 * <p>Units: target angular velocity is radians/second; maximum motor impulse is
 * in {@code N·m·s}.</p>
 */
public interface MotorConstraintHandle extends ConstraintHandle {
    /** Whether the motor is currently enabled. */
    boolean motorEnabled();

    /** Enables or disables the motor, preserving its current target and impulse. */
    void setMotorEnabled(boolean enabled);

    /** Current target angular velocity in radians/second. */
    double motorTargetVelocity();

    /** Sets the target angular velocity in radians/second. */
    void setMotorTargetVelocity(double velocity);

    /** Current maximum angular impulse per step in {@code N·m·s}. */
    double maxMotorImpulse();

    /** Sets the maximum angular impulse per step in {@code N·m·s}. */
    void setMaxMotorImpulse(double maxImpulse);
}
