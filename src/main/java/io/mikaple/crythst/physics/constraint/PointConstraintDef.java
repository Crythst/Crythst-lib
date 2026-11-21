package io.mikaple.crythst.physics.constraint;

import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Point-to-point joint, useful for ropes and loose linkages.
 */
public record PointConstraintDef(
        BodyHandle bodyA,
        BodyHandle bodyB,
        Vector3d pivotA,
        Vector3d pivotB
) implements ConstraintDef {
    public PointConstraintDef {
        Validate.notNull(bodyA, "bodyA");
        pivotA = new Vector3d(Validate.notNull(pivotA, "pivotA"));
        pivotB = new Vector3d(Validate.notNull(pivotB, "pivotB"));
    }

    public static PointConstraintDef between(BodyHandle bodyA, BodyHandle bodyB,
                                             Vector3dc pivotA, Vector3dc pivotB) {
        return new PointConstraintDef(bodyA, bodyB, new Vector3d(pivotA), new Vector3d(pivotB));
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.POINT;
    }
}
