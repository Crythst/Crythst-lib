package io.mikaple.crythst.physics.constraint;

import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Hinge joint. The hinge axis is given in each body's local space; the two
 * vectors must describe the same world direction in the rest pose.
 *
 * @param lowerLimit          lower angle limit in radians
 * @param upperLimit          upper angle limit in radians
 * @param enableMotor         whether the angular motor is active
 * @param motorTargetVelocity target angular velocity in radians/second
 * @param maxMotorImpulse     maximum motor impulse per step
 */
public record HingeConstraintDef(
        BodyHandle bodyA,
        BodyHandle bodyB,
        Vector3d pivotA,
        Vector3d pivotB,
        Vector3d axisA,
        Vector3d axisB,
        double lowerLimit,
        double upperLimit,
        boolean enableMotor,
        double motorTargetVelocity,
        double maxMotorImpulse
) implements ConstraintDef {
    public HingeConstraintDef {
        Validate.notNull(bodyA, "bodyA");
        pivotA = new Vector3d(Validate.notNull(pivotA, "pivotA"));
        pivotB = new Vector3d(Validate.notNull(pivotB, "pivotB"));
        axisA = new Vector3d(Validate.notNull(axisA, "axisA")).normalize();
        axisB = new Vector3d(Validate.notNull(axisB, "axisB")).normalize();
        Validate.finite(lowerLimit, "lowerLimit");
        Validate.finite(upperLimit, "upperLimit");
        Validate.finite(motorTargetVelocity, "motorTargetVelocity");
        Validate.nonNegative(maxMotorImpulse, "maxMotorImpulse");
    }

    public static HingeConstraintDef free(BodyHandle bodyA, BodyHandle bodyB,
                                          Vector3dc pivotA, Vector3dc pivotB,
                                          Vector3dc axisA, Vector3dc axisB) {
        return new HingeConstraintDef(bodyA, bodyB, new Vector3d(pivotA), new Vector3d(pivotB),
                new Vector3d(axisA), new Vector3d(axisB),
                (double) -Math.PI, Math.PI, false, 0.0, 0.0);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.HINGE;
    }
}
