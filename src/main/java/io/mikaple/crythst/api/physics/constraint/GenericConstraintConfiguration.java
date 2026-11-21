package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.SliderConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;

/**
 * Prismatic slider joint (a small subset of a 6-DOF joint).
 *
 * <p>The joint frame's local <strong>+X</strong> axis is the slide direction.
 * Translation is free along X within the linear limits; translation along Y and
 * Z is locked. Rotation about X is limited by the angular limits; rotation about
 * Y and Z is locked. This is a 1-DOF prismatic joint, not a full 6-DOF joint.</p>
 *
 * <p>{@code frameA}/{@code frameB} are structure-local frames; the frame
 * rotation defines the slide axis. For a world-anchored joint, {@code frameB} is
 * a world-space frame. The current backend's single-body slider constructor uses
 * an identity frame basis, so for world-anchored sliders the slide axis follows
 * structure A's local +X and the frame rotations are ignored; use a
 * two-structure configuration when a rotated slide axis is required.</p>
 *
 * <p><strong>Units:</strong> frame positions and linear limits are metres;
 * angular limits are radians; target linear velocity is metres/second;
 * {@code maxLinearForce} is newtons.</p>
 *
 * <p>See {@link StructureConstraintConfiguration} for frame conventions and
 * units.</p>
 */
public record GenericConstraintConfiguration(
        Transform3d frameA,
        Transform3d frameB,
        double lowerLinearLimit,
        double upperLinearLimit,
        double lowerAngularLimit,
        double upperAngularLimit,
        boolean motorEnabled,
        double targetLinearVelocity,
        double maxLinearForce
) implements StructureConstraintConfiguration {

    public GenericConstraintConfiguration {
        Validate.notNull(frameA, "frameA");
        Validate.notNull(frameB, "frameB");
        Validate.finite(lowerLinearLimit, "lowerLinearLimit");
        Validate.finite(upperLinearLimit, "upperLinearLimit");
        Validate.finite(lowerAngularLimit, "lowerAngularLimit");
        Validate.finite(upperAngularLimit, "upperAngularLimit");
        if (lowerLinearLimit > upperLinearLimit) {
            throw new IllegalArgumentException("lowerLinearLimit must not exceed upperLinearLimit: "
                    + lowerLinearLimit + " > " + upperLinearLimit);
        }
        if (lowerAngularLimit > upperAngularLimit) {
            throw new IllegalArgumentException("lowerAngularLimit must not exceed upperAngularLimit: "
                    + lowerAngularLimit + " > " + upperAngularLimit);
        }
        Validate.finite(targetLinearVelocity, "targetLinearVelocity");
        Validate.nonNegative(maxLinearForce, "maxLinearForce");
    }

    /**
     * Creates a slider between two structures with the given linear travel.
     *
     * <p>Rotational limits default to zero, so the two frames keep their relative
     * orientation.</p>
     */
    public static GenericConstraintConfiguration slider(Transform3d frameA, Transform3d frameB,
                                                        double lowerLinearLimit,
                                                        double upperLinearLimit) {
        return new GenericConstraintConfiguration(frameA, frameB,
                lowerLinearLimit, upperLinearLimit, 0.0, 0.0, false, 0.0, 0.0);
    }

    /**
     * Creates a slider from structure A to the world with the given linear
     * travel. The structure-A frame is the identity and the world frame is
     * {@code worldFrame}.
     */
    public static GenericConstraintConfiguration toWorld(Transform3d worldFrame,
                                                         double lowerLinearLimit,
                                                         double upperLinearLimit) {
        return new GenericConstraintConfiguration(Transform3d.IDENTITY, worldFrame,
                lowerLinearLimit, upperLinearLimit, 0.0, 0.0, false, 0.0, 0.0);
    }

    /**
     * Creates a slider between two structures with one metre of travel in each
     * direction and locked rotation.
     */
    public static GenericConstraintConfiguration free(Transform3d frameA, Transform3d frameB) {
        return new GenericConstraintConfiguration(frameA, frameB,
                -1.0, 1.0, 0.0, 0.0, false, 0.0, 0.0);
    }

    /** Returns a copy with a different structure-A-local frame. */
    public GenericConstraintConfiguration withFrameA(Transform3d newFrameA) {
        return new GenericConstraintConfiguration(newFrameA, frameB,
                lowerLinearLimit, upperLinearLimit, lowerAngularLimit, upperAngularLimit,
                motorEnabled, targetLinearVelocity, maxLinearForce);
    }

    /** Returns a copy with a different structure-B-local or world frame. */
    public GenericConstraintConfiguration withFrameB(Transform3d newFrameB) {
        return new GenericConstraintConfiguration(frameA, newFrameB,
                lowerLinearLimit, upperLinearLimit, lowerAngularLimit, upperAngularLimit,
                motorEnabled, targetLinearVelocity, maxLinearForce);
    }

    /** Returns a copy with different linear travel limits in metres. */
    public GenericConstraintConfiguration withLinearLimits(double newLowerLimit,
                                                           double newUpperLimit) {
        return new GenericConstraintConfiguration(frameA, frameB,
                newLowerLimit, newUpperLimit, lowerAngularLimit, upperAngularLimit,
                motorEnabled, targetLinearVelocity, maxLinearForce);
    }

    /** Returns a copy with different angular limits in radians. */
    public GenericConstraintConfiguration withAngularLimits(double newLowerLimit,
                                                            double newUpperLimit) {
        return new GenericConstraintConfiguration(frameA, frameB,
                lowerLinearLimit, upperLinearLimit, newLowerLimit, newUpperLimit,
                motorEnabled, targetLinearVelocity, maxLinearForce);
    }

    /**
     * Returns a copy with the linear motor enabled.
     *
     * @param targetVelocity target linear velocity in metres/second
     * @param maxForce       maximum linear force in newtons
     */
    public GenericConstraintConfiguration withMotor(double targetVelocity, double maxForce) {
        return new GenericConstraintConfiguration(frameA, frameB,
                lowerLinearLimit, upperLinearLimit, lowerAngularLimit, upperAngularLimit,
                true, targetVelocity, maxForce);
    }

    /** Returns a copy with the linear motor disabled. */
    public GenericConstraintConfiguration withoutMotor() {
        return new GenericConstraintConfiguration(frameA, frameB,
                lowerLinearLimit, upperLinearLimit, lowerAngularLimit, upperAngularLimit,
                false, targetLinearVelocity, maxLinearForce);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.SLIDER;
    }

    @Override
    public ConstraintDef toDefinition(PhysicsStructure structureA,
                                      @Nullable PhysicsStructure structureB) {
        final Transform3d bodyFrameA = ConstraintFrames.structureToBody(structureA, frameA);
        if (structureB == null) {
            return new SliderConstraintDef(structureA.body(), null, bodyFrameA, frameB,
                    lowerLinearLimit, upperLinearLimit, lowerAngularLimit, upperAngularLimit,
                    motorEnabled, targetLinearVelocity, maxLinearForce);
        }
        return new SliderConstraintDef(structureA.body(), structureB.body(), bodyFrameA,
                ConstraintFrames.structureToBody(structureB, frameB),
                lowerLinearLimit, upperLinearLimit, lowerAngularLimit, upperAngularLimit,
                motorEnabled, targetLinearVelocity, maxLinearForce);
    }
}
