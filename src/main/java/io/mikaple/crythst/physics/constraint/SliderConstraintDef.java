package io.mikaple.crythst.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.util.Validate;

/**
 * Slider (prismatic) joint. Frames are expressed in each body's local space; the
 * joint slides along frame X.
 */
public record SliderConstraintDef(
        BodyHandle bodyA,
        BodyHandle bodyB,
        Transform3d frameA,
        Transform3d frameB,
        double lowerLinearLimit,
        double upperLinearLimit,
        double lowerAngularLimit,
        double upperAngularLimit,
        boolean enableLinearMotor,
        double targetLinearVelocity,
        double maxLinearForce
) implements ConstraintDef {
    public SliderConstraintDef {
        Validate.notNull(bodyA, "bodyA");
        frameA = frameA == null ? Transform3d.IDENTITY : frameA;
        frameB = frameB == null ? Transform3d.IDENTITY : frameB;
        Validate.finite(lowerLinearLimit, "lowerLinearLimit");
        Validate.finite(upperLinearLimit, "upperLinearLimit");
        Validate.finite(lowerAngularLimit, "lowerAngularLimit");
        Validate.finite(upperAngularLimit, "upperAngularLimit");
        Validate.finite(targetLinearVelocity, "targetLinearVelocity");
        Validate.nonNegative(maxLinearForce, "maxLinearForce");
    }

    public static SliderConstraintDef free(BodyHandle bodyA, BodyHandle bodyB,
                                           Transform3d frameA, Transform3d frameB) {
        return new SliderConstraintDef(bodyA, bodyB, frameA, frameB,
                -1.0, 1.0, 0.0, 0.0, false, 0.0, 0.0);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.SLIDER;
    }
}
