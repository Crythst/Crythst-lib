package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.DofAxisDef;
import io.mikaple.crythst.physics.constraint.SixDofConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Full six-degree-of-freedom joint with independent limits, motors and springs
 * on every axis.
 *
 * <p>{@code frameA} is structure-A-local; {@code frameB} is structure-B-local,
 * or world-space for a world-anchored joint. The frame rotation defines the
 * joint's local axes. Axis order is X/Y/Z translation followed by X/Y/Z
 * rotation.</p>
 *
 * <p>This maps to Bullet's {@code btGeneric6DofSpring2Constraint}
 * ({@code New6Dof} in libbulletjme). The older fixed/point/hinge/slider
 * configurations keep their existing backend joints and behaviour.</p>
 *
 * <p><strong>Units:</strong> frame positions and linear limits are metres;
 * angular limits are radians; target linear/angular velocities are m/s and
 * rad/s; forces are newtons/newton-metres; damping is Bullet's dimensionless
 * spring damping ratio.</p>
 *
 * <p>See {@link StructureConstraintConfiguration} for frame conventions.</p>
 */
public record SixDofConstraintConfiguration(
        Transform3d frameA,
        Transform3d frameB,
        DofAxisConfiguration linearX,
        DofAxisConfiguration linearY,
        DofAxisConfiguration linearZ,
        DofAxisConfiguration angularX,
        DofAxisConfiguration angularY,
        DofAxisConfiguration angularZ
) implements StructureConstraintConfiguration {

    public SixDofConstraintConfiguration {
        Validate.notNull(frameA, "frameA");
        Validate.notNull(frameB, "frameB");
        Validate.notNull(linearX, "linearX");
        Validate.notNull(linearY, "linearY");
        Validate.notNull(linearZ, "linearZ");
        Validate.notNull(angularX, "angularX");
        Validate.notNull(angularY, "angularY");
        Validate.notNull(angularZ, "angularZ");
    }

    /** A joint between two structures with the supplied per-axis settings. */
    public static SixDofConstraintConfiguration between(
            Transform3d frameA, Transform3d frameB,
            DofAxisConfiguration linearX, DofAxisConfiguration linearY,
            DofAxisConfiguration linearZ, DofAxisConfiguration angularX,
            DofAxisConfiguration angularY, DofAxisConfiguration angularZ) {
        return new SixDofConstraintConfiguration(frameA, frameB,
                linearX, linearY, linearZ, angularX, angularY, angularZ);
    }

    /** A joint from structure A to a world-space frame. */
    public static SixDofConstraintConfiguration toWorld(
            Transform3d worldFrame,
            DofAxisConfiguration linearX, DofAxisConfiguration linearY,
            DofAxisConfiguration linearZ, DofAxisConfiguration angularX,
            DofAxisConfiguration angularY, DofAxisConfiguration angularZ) {
        return new SixDofConstraintConfiguration(Transform3d.IDENTITY, worldFrame,
                linearX, linearY, linearZ, angularX, angularY, angularZ);
    }

    /** All six axes locked at zero (a 6-DOF weld). */
    public static SixDofConstraintConfiguration locked(Transform3d frameA, Transform3d frameB) {
        final DofAxisConfiguration locked = DofAxisConfiguration.locked();
        return new SixDofConstraintConfiguration(frameA, frameB,
                locked, locked, locked, locked, locked, locked);
    }

    /** All six axes free. */
    public static SixDofConstraintConfiguration free(Transform3d frameA, Transform3d frameB) {
        final DofAxisConfiguration free = DofAxisConfiguration.free();
        return new SixDofConstraintConfiguration(frameA, frameB,
                free, free, free, free, free, free);
    }

    /** All six axes free from structure A to a world frame. */
    public static SixDofConstraintConfiguration freeToWorld(Transform3d worldFrame) {
        return toWorld(worldFrame,
                DofAxisConfiguration.free(), DofAxisConfiguration.free(),
                DofAxisConfiguration.free(), DofAxisConfiguration.free(),
                DofAxisConfiguration.free(), DofAxisConfiguration.free());
    }

    /** Returns the configuration of one axis. */
    public DofAxisConfiguration axis(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return switch (axis) {
            case LINEAR_X -> linearX;
            case LINEAR_Y -> linearY;
            case LINEAR_Z -> linearZ;
            case ANGULAR_X -> angularX;
            case ANGULAR_Y -> angularY;
            case ANGULAR_Z -> angularZ;
        };
    }

    /** Returns the six axes in Bullet index order. */
    public List<DofAxisConfiguration> axes() {
        return List.of(linearX, linearY, linearZ, angularX, angularY, angularZ);
    }

    /** Returns a copy with one axis replaced. */
    public SixDofConstraintConfiguration withAxis(SixDofAxis axis,
                                                  DofAxisConfiguration configuration) {
        Validate.notNull(axis, "axis");
        Validate.notNull(configuration, "configuration");
        return switch (axis) {
            case LINEAR_X -> new SixDofConstraintConfiguration(frameA, frameB,
                    configuration, linearY, linearZ, angularX, angularY, angularZ);
            case LINEAR_Y -> new SixDofConstraintConfiguration(frameA, frameB,
                    linearX, configuration, linearZ, angularX, angularY, angularZ);
            case LINEAR_Z -> new SixDofConstraintConfiguration(frameA, frameB,
                    linearX, linearY, configuration, angularX, angularY, angularZ);
            case ANGULAR_X -> new SixDofConstraintConfiguration(frameA, frameB,
                    linearX, linearY, linearZ, configuration, angularY, angularZ);
            case ANGULAR_Y -> new SixDofConstraintConfiguration(frameA, frameB,
                    linearX, linearY, linearZ, angularX, configuration, angularZ);
            case ANGULAR_Z -> new SixDofConstraintConfiguration(frameA, frameB,
                    linearX, linearY, linearZ, angularX, angularY, configuration);
        };
    }

    /** Returns a copy with a different structure-A-local frame. */
    public SixDofConstraintConfiguration withFrameA(Transform3d newFrameA) {
        return new SixDofConstraintConfiguration(newFrameA, frameB,
                linearX, linearY, linearZ, angularX, angularY, angularZ);
    }

    /** Returns a copy with a different structure-B-local or world frame. */
    public SixDofConstraintConfiguration withFrameB(Transform3d newFrameB) {
        return new SixDofConstraintConfiguration(frameA, newFrameB,
                linearX, linearY, linearZ, angularX, angularY, angularZ);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.SIX_DOF;
    }

    @Override
    public Transform3d localFrameA() {
        return frameA;
    }

    @Override
    public Transform3d localFrameB() {
        return frameB;
    }

    @Override
    public ConstraintDef toDefinition(PhysicsStructure structureA,
                                      @Nullable PhysicsStructure structureB) {
        final Transform3d bodyFrameA = ConstraintFrames.structureToBody(structureA, frameA);
        final Transform3d bodyFrameB = structureB == null
                ? frameB
                : ConstraintFrames.structureToBody(structureB, frameB);
        return new SixDofConstraintDef(structureA.body(),
                structureB == null ? null : structureB.body(),
                bodyFrameA, bodyFrameB, toAxisDefs());
    }

    private List<DofAxisDef> toAxisDefs() {
        final List<DofAxisConfiguration> axes = axes();
        final List<DofAxisDef> result = new ArrayList<>(6);
        for (DofAxisConfiguration axis : axes) {
            result.add(new DofAxisDef(axis.lowerLimit(), axis.upperLimit(),
                    axis.disabled(), axis.motorEnabled(), axis.targetVelocity(),
                    axis.maxMotorForce(), axis.springEnabled(), axis.stiffness(),
                    axis.damping()));
        }
        return result;
    }
}
