package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.MotorConstraintHandle;
import io.mikaple.crythst.physics.constraint.SixDofConstraintHandle;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Stable API view of one live constraint.
 *
 * <p>A handle is a lightweight wrapper around a backend
 * {@link ConstraintHandle}. It remains valid while the owning world is open,
 * the constraint has not been removed through this handle, and the involved
 * structures still exist. Removing a body directly through the raw physics
 * world also invalidates the handle indirectly because the structure/body is
 * gone.</p>
 *
 * <p><strong>Threading:</strong> server thread only.</p>
 *
 * <p>Lifecycle methods that touch the joint ({@link #enabled()},
 * {@link #setEnabled(boolean)} and the motor accessors) throw
 * {@link IllegalStateException} after {@link #remove()}; read-only accessors
 * such as {@link #id()} and {@link #type()} remain available.</p>
 */
public final class StructureConstraintHandle {
    private final PhysicsWorld world;
    private final ConstraintHandle handle;
    private final @Nullable PhysicsStructure structureA;
    private final @Nullable PhysicsStructure structureB;
    private final @Nullable StructureConstraintConfiguration configuration;
    private boolean removed;

    /**
     * Wraps a live constraint.
     *
     * <p>This constructor is public so {@code PhysicsPipeline} and low-level
     * tools can wrap constraints created through the physics world directly.
     * Mods normally receive handles from
     * {@link io.mikaple.crythst.api.physics.PhysicsPipeline#addConstraint}.</p>
     *
     * @param world      owning physics world; must not be {@code null}
     * @param handle     backend constraint handle; must not be {@code null}
     * @param structureA structure owning body A, or {@code null} when unknown
     * @param structureB structure owning body B, or {@code null} for a
     *                   world-anchored joint
     */
    public StructureConstraintHandle(PhysicsWorld world, ConstraintHandle handle,
                                     @Nullable PhysicsStructure structureA,
                                     @Nullable PhysicsStructure structureB) {
        this(world, handle, structureA, structureB, null);
    }

    /**
     * Wraps a live constraint while retaining its immutable configuration.
     *
     * @param configuration originating configuration, or {@code null} when the
     *                      joint was created directly through the physics world
     */
    public StructureConstraintHandle(PhysicsWorld world, ConstraintHandle handle,
                                     @Nullable PhysicsStructure structureA,
                                     @Nullable PhysicsStructure structureB,
                                     @Nullable StructureConstraintConfiguration configuration) {
        this.world = Validate.notNull(world, "world");
        this.handle = Validate.notNull(handle, "handle");
        if (structureA != null && structureA.world() != world) {
            throw new IllegalArgumentException("structureA belongs to a different physics world");
        }
        if (structureB != null && structureB.world() != world) {
            throw new IllegalArgumentException("structureB belongs to a different physics world");
        }
        this.structureA = structureA;
        this.structureB = structureB;
        final StructureConstraintConfiguration retained = configuration != null
                ? configuration
                : handle.metadata() instanceof StructureConstraintConfiguration metadata
                        ? metadata : null;
        this.configuration = retained;
        if (configuration != null) {
            // Keep the API configuration discoverable on later wrappers (for
            // example PhysicsPipeline.constraints() and persistence).
            handle.setMetadata(configuration);
        }
    }

    /**
     * @return {@code true} while the world is open, this handle has not been
     *         removed, and the involved structures still exist
     */
    public boolean isValid() {
        if (removed || world.closed() || handle.removed()) {
            return false;
        }
        if (structureA != null && (structureA.closed() || !structureA.body().inWorld())) {
            return false;
        }
        return structureB == null || (!structureB.closed() && structureB.body().inWorld());
    }

    /** Numeric constraint id, unique within its physics world. */
    public long id() {
        return handle.id();
    }

    /** Backend joint family. */
    public ConstraintType type() {
        return handle.type();
    }

    /** Structure owning body A, or {@code null} when the handle was wrapped without one. */
    public @Nullable PhysicsStructure structureA() {
        return structureA;
    }

    /** Structure owning body B, or {@code null} for a world-anchored joint. */
    public @Nullable PhysicsStructure structureB() {
        return structureB;
    }

    /** Whether the second endpoint is the static world. */
    public boolean isWorldAnchored() {
        return structureB == null;
    }

    /**
     * The immutable configuration used to create this joint, when known.
     *
     * <p>This is the value the caller passed to
     * {@code PhysicsPipeline#addConstraint}. Constraints wrapped from the raw
     * physics world report {@code null}. The configuration is not a live
     * snapshot of limits or motor state; use the handle accessors for those.</p>
     */
    public @Nullable StructureConstraintConfiguration configuration() {
        return configuration;
    }

    /**
     * Attachment frame in structure-A-local metres, when the handle retains an
     * API configuration. Anchor-based joints return an identity rotation at
     * their anchor.
     */
    public @Nullable Transform3d localFrameA() {
        return configuration == null ? null : configuration.localFrameA();
    }

    /**
     * Attachment frame in structure-B-local metres, or world-space metres for a
     * world-anchored joint.
     */
    public @Nullable Transform3d localFrameB() {
        return configuration == null ? null : configuration.localFrameB();
    }

    /** Structure-A-local anchor point, or {@code null} when unknown. */
    public @Nullable Vector3d localAnchorA() {
        final Transform3d frame = localFrameA();
        return frame == null ? null : frame.position();
    }

    /**
     * Structure-B-local anchor, or the world anchor for a world-anchored joint.
     */
    public @Nullable Vector3d localAnchorB() {
        final Transform3d frame = localFrameB();
        return frame == null ? null : frame.position();
    }

    /** Current world-space frame A, or {@code null} when unknown. */
    public @Nullable Transform3d worldFrameA() {
        final Transform3d frame = localFrameA();
        if (frame == null || structureA == null) {
            return null;
        }
        return ConstraintFrames.structureToWorld(structureA, frame);
    }

    /**
     * Current world-space frame B. For a world-anchored joint this is the
     * stored world frame unchanged.
     */
    public @Nullable Transform3d worldFrameB() {
        final Transform3d frame = localFrameB();
        if (frame == null) {
            return null;
        }
        return structureB == null
                ? frame
                : ConstraintFrames.structureToWorld(structureB, frame);
    }

    /** Current world-space anchor A, or {@code null} when unknown. */
    public @Nullable Vector3d worldAnchorA() {
        final Transform3d frame = worldFrameA();
        return frame == null ? null : frame.position();
    }

    /** Current world-space anchor B, or {@code null} when unknown. */
    public @Nullable Vector3d worldAnchorB() {
        final Transform3d frame = worldFrameB();
        return frame == null ? null : frame.position();
    }

    /**
     * Rotation of the structure-A-local axis frame, or {@code null} when the
     * handle retains no configuration.
     */
    public @Nullable Quaterniond axisQuaternionA() {
        final Transform3d frame = localFrameA();
        return frame == null ? null : frame.rotation();
    }

    /** Rotation of the structure-B-local or world axis frame. */
    public @Nullable Quaterniond axisQuaternionB() {
        final Transform3d frame = localFrameB();
        return frame == null ? null : frame.rotation();
    }

    /**
     * Hinge axis in structure-A-local space for rotary joints, otherwise
     * {@code null}. Use {@link #axisQuaternionA()} for frame-based joints.
     */
    public @Nullable Vector3d localAxisA() {
        return configuration instanceof RotaryConstraintConfiguration rotary
                ? rotary.axisA() : null;
    }

    /** Hinge axis in structure-B-local or world space for rotary joints. */
    public @Nullable Vector3d localAxisB() {
        return configuration instanceof RotaryConstraintConfiguration rotary
                ? rotary.axisB() : null;
    }

    /** Current world-space hinge axis A, or {@code null}. */
    public @Nullable Vector3d worldAxisA() {
        final Vector3d axis = localAxisA();
        if (axis == null || structureA == null) {
            return null;
        }
        return ConstraintFrames.structureDirectionToWorld(structureA, axis);
    }

    /** Current world-space hinge axis B, or {@code null}. */
    public @Nullable Vector3d worldAxisB() {
        final Vector3d axis = localAxisB();
        if (axis == null) {
            return null;
        }
        return structureB == null
                ? new Vector3d(axis)
                : ConstraintFrames.structureDirectionToWorld(structureB, axis);
    }

    /**
     * Solver impulse applied during the most recent step, in newton-seconds.
     * Backends that do not expose feedback return zero.
     */
    public double appliedImpulse() {
        ensureUsable();
        return handle.appliedImpulse();
    }

    /** Whether solver impulse feedback is currently enabled. */
    public boolean feedbackEnabled() {
        ensureUsable();
        return handle.feedbackEnabled();
    }

    /** Enables or disables solver impulse feedback. */
    public void setFeedbackEnabled(boolean enabled) {
        ensureUsable();
        handle.setFeedbackEnabled(enabled);
    }

    /** Whether this joint supports per-axis 6-DOF control. */
    public boolean supportsDofControl() {
        return isValid() && handle instanceof SixDofConstraintHandle;
    }

    /**
     * Current configuration of one axis, read from the live backend when
     * possible and otherwise from the retained API configuration.
     */
    public DofAxisConfiguration dofConfiguration(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        final SixDofConstraintHandle sixDof = dof(axis, "dofConfiguration");
        final int index = axis.index();
        return new DofAxisConfiguration(
                sixDof.isAxisFree(index),
                sixDof.lowerLimit(index),
                sixDof.upperLimit(index),
                sixDof.isMotorEnabled(index),
                sixDof.motorTargetVelocity(index),
                sixDof.maxMotorForce(index),
                sixDof.isSpringEnabled(index),
                sixDof.stiffness(index),
                sixDof.damping(index));
    }

    /** Whether the axis is unconstrained (free). */
    public boolean isAxisFree(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "isAxisFree").isAxisFree(axis.index());
    }

    /** Makes an axis free or locks it at zero. */
    public void setAxisFree(SixDofAxis axis, boolean free) {
        Validate.notNull(axis, "axis");
        dof(axis, "setAxisFree").setAxisFree(axis.index(), free);
    }

    /** Current lower limit (metres or radians). */
    public double lowerLimit(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "lowerLimit").lowerLimit(axis.index());
    }

    /** Current upper limit (metres or radians). */
    public double upperLimit(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "upperLimit").upperLimit(axis.index());
    }

    /** Sets a constrained axis range. Use {@link #setAxisFree} for no range. */
    public void setLimits(SixDofAxis axis, double lowerLimit, double upperLimit) {
        Validate.notNull(axis, "axis");
        dof(axis, "setLimits").setLimits(axis.index(), lowerLimit, upperLimit);
    }

    /** Whether the axis motor is enabled. */
    public boolean isDofMotorEnabled(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "isDofMotorEnabled").isMotorEnabled(axis.index());
    }

    /** Enables or disables the axis motor, preserving target and force. */
    public void setDofMotorEnabled(SixDofAxis axis, boolean enabled) {
        Validate.notNull(axis, "axis");
        dof(axis, "setDofMotorEnabled").setMotorEnabled(axis.index(), enabled);
    }

    /** Axis motor target velocity (m/s or rad/s). */
    public double dofMotorTargetVelocity(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "dofMotorTargetVelocity").motorTargetVelocity(axis.index());
    }

    /** Sets the axis motor target velocity (m/s or rad/s). */
    public void setDofMotorTargetVelocity(SixDofAxis axis, double velocity) {
        Validate.notNull(axis, "axis");
        dof(axis, "setDofMotorTargetVelocity").setMotorTargetVelocity(axis.index(), velocity);
    }

    /** Axis motor maximum force (N or N·m). */
    public double dofMaxMotorForce(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "dofMaxMotorForce").maxMotorForce(axis.index());
    }

    /** Sets the axis motor maximum force (N or N·m). */
    public void setDofMaxMotorForce(SixDofAxis axis, double maxForce) {
        Validate.notNull(axis, "axis");
        dof(axis, "setDofMaxMotorForce").setMaxMotorForce(axis.index(), maxForce);
    }

    /** Enables or disables an axis motor in one call. */
    public void setDofMotor(SixDofAxis axis, boolean enabled, double targetVelocity,
                            double maxForce) {
        Validate.notNull(axis, "axis");
        final SixDofConstraintHandle sixDof = dof(axis, "setDofMotor");
        final int index = axis.index();
        sixDof.setMotorTargetVelocity(index, targetVelocity);
        sixDof.setMaxMotorForce(index, maxForce);
        sixDof.setMotorEnabled(index, enabled);
    }

    /** Whether the axis spring is enabled. */
    public boolean isDofSpringEnabled(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "isDofSpringEnabled").isSpringEnabled(axis.index());
    }

    /** Enables or disables an axis spring. */
    public void setDofSpringEnabled(SixDofAxis axis, boolean enabled) {
        Validate.notNull(axis, "axis");
        dof(axis, "setDofSpringEnabled").setSpringEnabled(axis.index(), enabled);
    }

    /** Axis spring stiffness. */
    public double dofStiffness(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "dofStiffness").stiffness(axis.index());
    }

    /** Sets axis spring stiffness. */
    public void setDofStiffness(SixDofAxis axis, double stiffness) {
        Validate.notNull(axis, "axis");
        dof(axis, "setDofStiffness").setStiffness(axis.index(), stiffness);
    }

    /** Axis spring damping ratio. */
    public double dofDamping(SixDofAxis axis) {
        Validate.notNull(axis, "axis");
        return dof(axis, "dofDamping").damping(axis.index());
    }

    /** Sets axis spring damping ratio. */
    public void setDofDamping(SixDofAxis axis, double damping) {
        Validate.notNull(axis, "axis");
        dof(axis, "setDofDamping").setDamping(axis.index(), damping);
    }

    /** Enables or disables an axis spring in one call. */
    public void setDofSpring(SixDofAxis axis, boolean enabled, double stiffness,
                             double damping) {
        Validate.notNull(axis, "axis");
        final SixDofConstraintHandle sixDof = dof(axis, "setDofSpring");
        final int index = axis.index();
        sixDof.setStiffness(index, stiffness);
        sixDof.setDamping(index, damping);
        sixDof.setSpringEnabled(index, enabled);
    }

    private SixDofConstraintHandle dof(SixDofAxis axis, String operation) {
        ensureUsable();
        if (handle.type() != ConstraintType.SIX_DOF
                || !(handle instanceof SixDofConstraintHandle sixDof)) {
            throw new UnsupportedOperationException(operation
                    + " is only supported for 6-DOF joints; got " + handle.type());
        }
        return sixDof;
    }

    /**
     * Body id of side A, or {@code -1} when the handle has no resolved
     * structure.
     */
    public long bodyIdA() {
        return structureA == null ? -1L : structureA.body().id();
    }

    /**
     * Body id of side B, or {@code -1} for a world-anchored joint or an
     * unresolved structure.
     */
    public long bodyIdB() {
        return structureB == null ? -1L : structureB.body().id();
    }

    /** Whether the joint currently participates in simulation. */
    public boolean enabled() {
        ensureUsable();
        return handle.enabled();
    }

    /** Enables or disables the joint without destroying it. */
    public void setEnabled(boolean enabled) {
        ensureUsable();
        handle.setEnabled(enabled);
    }

    /**
     * Removes the joint from the world. Idempotent.
     *
     * <p>Removing the joint directly through the raw world does not update this
     * wrapper; call {@link #isValid()} before relying on it after external
     * mutations.</p>
     */
    public void remove() {
        if (removed) {
            return;
        }
        if (world.closed()) {
            removed = true;
            return;
        }
        world.removeConstraint(handle);
        removed = true;
    }

    /**
     * Whether this joint references the given structure's body.
     *
     * @param structure structure to test; {@code null} returns {@code false}
     */
    public boolean involves(@Nullable PhysicsStructure structure) {
        if (structure == null || !isValid()) {
            return false;
        }
        if (structure == structureA || structure == structureB) {
            return true;
        }
        return handle.involves(structure.body());
    }

    /**
     * Whether this pipeline's world owns the wrapped constraint.
     *
     * <p>Low-level ownership check used by
     * {@link io.mikaple.crythst.api.physics.PhysicsPipeline#removeConstraint}.</p>
     */
    public boolean belongsTo(PhysicsWorld otherWorld) {
        return world == otherWorld;
    }

    /**
     * Whether runtime motor control is available for this joint.
     *
     * <p>Currently only hinge joints on the Bullet backend expose a motor. The
     * initial motor state is set through
     * {@link RotaryConstraintConfiguration}.</p>
     */
    public boolean supportsMotor() {
        return isValid()
                && handle.type() == ConstraintType.HINGE
                && handle instanceof MotorConstraintHandle;
    }

    /** Whether the rotary motor is enabled. */
    public boolean isMotorEnabled() {
        return motor("isMotorEnabled").motorEnabled();
    }

    /** Enables or disables the rotary motor, preserving target and impulse. */
    public void setMotorEnabled(boolean enabled) {
        motor("setMotorEnabled").setMotorEnabled(enabled);
    }

    /** Current rotary motor target velocity in radians/second. */
    public double motorTargetVelocity() {
        return motor("motorTargetVelocity").motorTargetVelocity();
    }

    /** Sets the rotary motor target velocity in radians/second. */
    public void setMotorTargetVelocity(double velocity) {
        motor("setMotorTargetVelocity").setMotorTargetVelocity(velocity);
    }

    /** Current maximum rotary motor impulse per step in {@code N·m·s}. */
    public double maxMotorImpulse() {
        return motor("maxMotorImpulse").maxMotorImpulse();
    }

    /** Sets the maximum rotary motor impulse per step in {@code N·m·s}. */
    public void setMaxMotorImpulse(double maxImpulse) {
        motor("setMaxMotorImpulse").setMaxMotorImpulse(maxImpulse);
    }

    private MotorConstraintHandle motor(String operation) {
        ensureUsable();
        if (handle.type() != ConstraintType.HINGE
                || !(handle instanceof MotorConstraintHandle)) {
            throw new UnsupportedOperationException(operation
                    + " is only supported for hinge joints with backend motor access; got "
                    + handle.type());
        }
        return (MotorConstraintHandle) handle;
    }

    private void ensureUsable() {
        if (!isValid()) {
            throw new IllegalStateException("Constraint #" + handle.id() + " is no longer valid");
        }
    }

    @Override
    public String toString() {
        return "StructureConstraintHandle[" + handle.id() + ", " + handle.type()
                + (removed ? ", removed" : "") + "]";
    }
}
