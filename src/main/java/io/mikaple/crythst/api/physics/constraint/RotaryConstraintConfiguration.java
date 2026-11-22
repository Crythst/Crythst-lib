package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.HingeConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Hinge joint with one rotational degree of freedom.
 *
 * <p>The hinge anchor and axis are structure-local: {@code anchorA}/{@code axisA}
 * belong to structure A and {@code anchorB}/{@code axisB} to structure B. For a
 * world-anchored joint, B is the world and its anchor/axis are world-space. The
 * axes need not be normalized; they are normalized by the backend. In the rest
 * pose the two axes should describe the same world direction.</p>
 *
 * <p><strong>Units:</strong> anchors are metres; limits and motor target
 * velocity are radians and radians per second; {@code maxMotorImpulse} is a
 * per-step angular impulse in {@code N·m·s}.</p>
 *
 * <p>See {@link StructureConstraintConfiguration} for frame conventions and
 * units.</p>
 */
public record RotaryConstraintConfiguration(
        Vector3d anchorA,
        Vector3d axisA,
        Vector3d anchorB,
        Vector3d axisB,
        double lowerLimit,
        double upperLimit,
        boolean motorEnabled,
        double motorTargetVelocity,
        double maxMotorImpulse
) implements StructureConstraintConfiguration {

    public RotaryConstraintConfiguration {
        anchorA = new Vector3d(Validate.notNull(anchorA, "anchorA"));
        axisA = requireAxis(axisA, "axisA");
        anchorB = new Vector3d(Validate.notNull(anchorB, "anchorB"));
        axisB = requireAxis(axisB, "axisB");
        Validate.finite(lowerLimit, "lowerLimit");
        Validate.finite(upperLimit, "upperLimit");
        if (lowerLimit > upperLimit) {
            throw new IllegalArgumentException(
                    "lowerLimit must not exceed upperLimit: " + lowerLimit + " > " + upperLimit);
        }
        Validate.finite(motorTargetVelocity, "motorTargetVelocity");
        Validate.nonNegative(maxMotorImpulse, "maxMotorImpulse");
    }

    private static Vector3d requireAxis(Vector3dc axis, String name) {
        Validate.notNull(axis, name);
        final Vector3d copy = new Vector3d(axis);
        if (!Double.isFinite(copy.x) || !Double.isFinite(copy.y) || !Double.isFinite(copy.z)
                || copy.lengthSquared() < 1.0e-20) {
            throw new IllegalArgumentException(name + " must be a finite non-zero direction: " + copy);
        }
        return copy;
    }

    /**
     * Creates a hinge between two structures.
     *
     * @param anchorA hinge anchor in structure A local metres
     * @param axisA   hinge axis in structure A local space
     * @param anchorB hinge anchor in structure B local metres
     * @param axisB   hinge axis in structure B local space
     */
    public static RotaryConstraintConfiguration between(Vector3dc anchorA, Vector3dc axisA,
                                                        Vector3dc anchorB, Vector3dc axisB) {
        return new RotaryConstraintConfiguration(new Vector3d(anchorA), new Vector3d(axisA),
                new Vector3d(anchorB), new Vector3d(axisB),
                -Math.PI, Math.PI, false, 0.0, 0.0);
    }

    /**
     * Creates a hinge from structure A to a world-space anchor and axis.
     *
     * <p>Both sides are explicit because the structure-local A axis cannot be
     * derived from a world axis before a structure pose is known.</p>
     *
     * @param structureAnchor hinge anchor in structure A local metres
     * @param structureAxis   hinge axis in structure A local space
     * @param worldAnchor     hinge anchor in world metres
     * @param worldAxis       hinge axis in world space
     */
    public static RotaryConstraintConfiguration toWorld(Vector3dc structureAnchor,
                                                        Vector3dc structureAxis,
                                                        Vector3dc worldAnchor,
                                                        Vector3dc worldAxis) {
        return new RotaryConstraintConfiguration(new Vector3d(structureAnchor),
                new Vector3d(structureAxis), new Vector3d(worldAnchor), new Vector3d(worldAxis),
                -Math.PI, Math.PI, false, 0.0, 0.0);
    }

    @Override
    public Vector3d anchorA() {
        return new Vector3d(anchorA);
    }

    @Override
    public Vector3d axisA() {
        return new Vector3d(axisA);
    }

    @Override
    public Vector3d anchorB() {
        return new Vector3d(anchorB);
    }

    @Override
    public Vector3d axisB() {
        return new Vector3d(axisB);
    }

    /** Returns a copy with a different structure-A-local anchor. */
    public RotaryConstraintConfiguration withAnchorA(Vector3dc newAnchorA) {
        return new RotaryConstraintConfiguration(new Vector3d(newAnchorA), axisA, anchorB, axisB,
                lowerLimit, upperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
    }

    /** Returns a copy with a different structure-A-local axis. */
    public RotaryConstraintConfiguration withAxisA(Vector3dc newAxisA) {
        return new RotaryConstraintConfiguration(anchorA, new Vector3d(newAxisA), anchorB, axisB,
                lowerLimit, upperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
    }

    /** Returns a copy with a different structure-B-local or world anchor. */
    public RotaryConstraintConfiguration withAnchorB(Vector3dc newAnchorB) {
        return new RotaryConstraintConfiguration(anchorA, axisA, new Vector3d(newAnchorB), axisB,
                lowerLimit, upperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
    }

    /** Returns a copy with a different structure-B-local or world axis. */
    public RotaryConstraintConfiguration withAxisB(Vector3dc newAxisB) {
        return new RotaryConstraintConfiguration(anchorA, axisA, anchorB, new Vector3d(newAxisB),
                lowerLimit, upperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
    }

    /**
     * Returns a copy with angular limits in radians.
     */
    public RotaryConstraintConfiguration withLimits(double newLowerLimit, double newUpperLimit) {
        return new RotaryConstraintConfiguration(anchorA, axisA, anchorB, axisB,
                newLowerLimit, newUpperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
    }

    /**
     * Returns a copy with the angular motor enabled.
     *
     * @param targetVelocity target angular velocity in radians/second
     * @param maxImpulse     maximum angular impulse per step in {@code N·m·s}
     */
    public RotaryConstraintConfiguration withMotor(double targetVelocity, double maxImpulse) {
        return new RotaryConstraintConfiguration(anchorA, axisA, anchorB, axisB,
                lowerLimit, upperLimit, true, targetVelocity, maxImpulse);
    }

    /** Returns a copy with the angular motor disabled. */
    public RotaryConstraintConfiguration withoutMotor() {
        return new RotaryConstraintConfiguration(anchorA, axisA, anchorB, axisB,
                lowerLimit, upperLimit, false, motorTargetVelocity, maxMotorImpulse);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.HINGE;
    }

    @Override
    public Transform3d localFrameA() {
        return Transform3d.of(anchorA);
    }

    @Override
    public Transform3d localFrameB() {
        return Transform3d.of(anchorB);
    }

    @Override
    public ConstraintDef toDefinition(PhysicsStructure structureA,
                                      @Nullable PhysicsStructure structureB) {
        final Vector3d pivotA = ConstraintFrames.structurePointToBody(structureA, anchorA);
        final Vector3d localAxisA = ConstraintFrames.structureDirectionToBody(structureA, axisA);
        if (structureB == null) {
            return new HingeConstraintDef(structureA.body(), null, pivotA, new Vector3d(anchorB),
                    localAxisA, new Vector3d(axisB),
                    lowerLimit, upperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
        }
        return new HingeConstraintDef(structureA.body(), structureB.body(), pivotA,
                ConstraintFrames.structurePointToBody(structureB, anchorB),
                localAxisA, ConstraintFrames.structureDirectionToBody(structureB, axisB),
                lowerLimit, upperLimit, motorEnabled, motorTargetVelocity, maxMotorImpulse);
    }
}
