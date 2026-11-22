package io.mikaple.crythst.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.util.Validate;

import java.util.List;

/**
 * Full six-degree-of-freedom joint. Axis order is X/Y/Z translation followed by
 * X/Y/Z rotation, matching Bullet's {@code btGeneric6DofSpring2Constraint}.
 *
 * <p>A {@code null} body B anchors the joint to the world. Frames use the same
 * body-local conventions as {@link FixedConstraintDef}: frame A is body-local,
 * frame B is body-local or world-space when body B is absent.</p>
 */
public record SixDofConstraintDef(
        BodyHandle bodyA,
        BodyHandle bodyB,
        Transform3d frameA,
        Transform3d frameB,
        List<DofAxisDef> axes
) implements ConstraintDef {
    public SixDofConstraintDef {
        Validate.notNull(bodyA, "bodyA");
        frameA = frameA == null ? Transform3d.IDENTITY : frameA;
        frameB = frameB == null ? Transform3d.IDENTITY : frameB;
        Validate.notNull(axes, "axes");
        if (axes.size() != 6) {
            throw new IllegalArgumentException("A 6-DOF joint needs exactly 6 axes; got " + axes.size());
        }
        for (int i = 0; i < axes.size(); i++) {
            Validate.notNull(axes.get(i), "axes[" + i + "]");
        }
        axes = List.copyOf(axes);
    }

    /** Returns the configuration of the axis with the given Bullet DOF index. */
    public DofAxisDef axis(int dofIndex) {
        if (dofIndex < 0 || dofIndex >= 6) {
            throw new IllegalArgumentException("dofIndex must be in [0, 5]: " + dofIndex);
        }
        return axes.get(dofIndex);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.SIX_DOF;
    }
}
