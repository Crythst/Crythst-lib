package io.mikaple.crythst.physics.constraint;

import io.mikaple.crythst.util.Validate;

/**
 * Backend-independent configuration of one degree of freedom of a 6-DOF
 * constraint.
 *
 * <p>The same values are interpreted in metres / metres per second / newtons
 * for the three linear axes and in radians / radians per second / newton-metres
 * for the three angular axes.</p>
 *
 * <p>{@code free} means the axis is not constrained (Bullet encodes this as
 * {@code lowerLimit > upperLimit}). A locked axis has both limits equal to
 * zero. Motors and springs are independent of the limit mode.</p>
 */
public record DofAxisDef(
        double lowerLimit,
        double upperLimit,
        boolean freeAxis,
        boolean enableMotor,
        double motorTargetVelocity,
        double maxMotorForce,
        boolean enableSpring,
        double stiffness,
        double damping
) {
    public DofAxisDef {
        Validate.finite(lowerLimit, "lowerLimit");
        Validate.finite(upperLimit, "upperLimit");
        if (!freeAxis && lowerLimit > upperLimit) {
            throw new IllegalArgumentException(
                    "lowerLimit must not exceed upperLimit unless the axis is free: "
                            + lowerLimit + " > " + upperLimit);
        }
        Validate.finite(motorTargetVelocity, "motorTargetVelocity");
        Validate.nonNegative(maxMotorForce, "maxMotorForce");
        Validate.nonNegative(stiffness, "stiffness");
        Validate.nonNegative(damping, "damping");
    }

    /** A locked axis at zero with no motor or spring. */
    public static DofAxisDef locked() {
        return new DofAxisDef(0.0, 0.0, false, false, 0.0, 0.0, false, 0.0, 0.0);
    }

    /** A free axis with no motor or spring. */
    public static DofAxisDef free() {
        return new DofAxisDef(0.0, 0.0, true, false, 0.0, 0.0, false, 0.0, 0.0);
    }
}
