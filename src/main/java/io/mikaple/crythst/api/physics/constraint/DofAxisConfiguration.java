package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.util.Validate;

/**
 * Immutable configuration of one degree of freedom for a full 6-DOF joint.
 *
 * <p>For linear axes, limits and motor target velocity are metres and metres
 * per second, maximum motor force is newtons. For angular axes, limits and
 * target velocity are radians and radians per second, maximum force is
 * newton-metres. Spring stiffness/damping use Bullet's spring parameters.</p>
 *
 * <p>{@link #disabled()} means the axis is <em>free</em> (unconstrained); it
 * still participates in the joint and may have a motor or spring. Use
 * {@link #locked()} to pin an axis at zero, {@link #limited(double, double)}
 * for a range, or {@link #free()} for no constraint. This distinction matters
 * because a locked axis and a free axis with a zero-width range are different
 * in Bullet.</p>
 */
public record DofAxisConfiguration(
        boolean disabled,
        double lowerLimit,
        double upperLimit,
        boolean motorEnabled,
        double targetVelocity,
        double maxMotorForce,
        boolean springEnabled,
        double stiffness,
        double damping
) {
    public DofAxisConfiguration {
        Validate.finite(lowerLimit, "lowerLimit");
        Validate.finite(upperLimit, "upperLimit");
        if (!disabled && lowerLimit > upperLimit) {
            throw new IllegalArgumentException(
                    "lowerLimit must not exceed upperLimit unless the axis is disabled: "
                            + lowerLimit + " > " + upperLimit);
        }
        Validate.finite(targetVelocity, "targetVelocity");
        Validate.nonNegative(maxMotorForce, "maxMotorForce");
        Validate.nonNegative(stiffness, "stiffness");
        Validate.nonNegative(damping, "damping");
    }

    /** An axis pinned at zero with no motor or spring. */
    public static DofAxisConfiguration locked() {
        return new DofAxisConfiguration(false, 0.0, 0.0,
                false, 0.0, 0.0, false, 0.0, 0.0);
    }

    /** An unconstrained axis with no motor or spring. */
    public static DofAxisConfiguration free() {
        return new DofAxisConfiguration(true, 0.0, 0.0,
                false, 0.0, 0.0, false, 0.0, 0.0);
    }

    /** An axis constrained to {@code [lowerLimit, upperLimit]}. */
    public static DofAxisConfiguration limited(double lowerLimit, double upperLimit) {
        return new DofAxisConfiguration(false, lowerLimit, upperLimit,
                false, 0.0, 0.0, false, 0.0, 0.0);
    }

    /** Returns a copy with the axis free/locked mode changed. */
    public DofAxisConfiguration withDisabled(boolean newDisabled) {
        return new DofAxisConfiguration(newDisabled, lowerLimit, upperLimit,
                motorEnabled, targetVelocity, maxMotorForce, springEnabled, stiffness, damping);
    }

    /** Returns a copy with different limits. */
    public DofAxisConfiguration withLimits(double newLowerLimit, double newUpperLimit) {
        return new DofAxisConfiguration(disabled, newLowerLimit, newUpperLimit,
                motorEnabled, targetVelocity, maxMotorForce, springEnabled, stiffness, damping);
    }

    /**
     * Returns a copy with a motor.
     *
     * @param target target velocity (m/s or rad/s)
     * @param maxForce maximum force (N or N·m)
     */
    public DofAxisConfiguration withMotor(double target, double maxForce) {
        return new DofAxisConfiguration(disabled, lowerLimit, upperLimit,
                true, target, maxForce, springEnabled, stiffness, damping);
    }

    /** Returns a copy with the motor disabled, preserving its target and force. */
    public DofAxisConfiguration withoutMotor() {
        return new DofAxisConfiguration(disabled, lowerLimit, upperLimit,
                false, targetVelocity, maxMotorForce, springEnabled, stiffness, damping);
    }

    /** Returns a copy with a spring. */
    public DofAxisConfiguration withSpring(double newStiffness, double newDamping) {
        return new DofAxisConfiguration(disabled, lowerLimit, upperLimit,
                motorEnabled, targetVelocity, maxMotorForce, true, newStiffness, newDamping);
    }

    /** Returns a copy with the spring disabled, preserving its parameters. */
    public DofAxisConfiguration withoutSpring() {
        return new DofAxisConfiguration(disabled, lowerLimit, upperLimit,
                motorEnabled, targetVelocity, maxMotorForce, false, stiffness, damping);
    }
}
