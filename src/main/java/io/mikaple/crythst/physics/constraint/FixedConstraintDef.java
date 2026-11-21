package io.mikaple.crythst.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.util.Validate;

/**
 * Welds two bodies together. A {@code null} body B anchors the joint to the
 * world.
 */
public record FixedConstraintDef(
        BodyHandle bodyA,
        BodyHandle bodyB,
        Transform3d frameA,
        Transform3d frameB
) implements ConstraintDef {
    public FixedConstraintDef {
        Validate.notNull(bodyA, "bodyA");
        frameA = frameA == null ? Transform3d.IDENTITY : frameA;
        frameB = frameB == null ? Transform3d.IDENTITY : frameB;
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.FIXED;
    }
}
