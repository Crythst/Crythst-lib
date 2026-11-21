package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.MotorConstraintHandle;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;

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
    }

    /**
     * @return {@code true} while the world is open, this handle has not been
     *         removed, and the involved structures still exist
     */
    public boolean isValid() {
        if (removed || world.closed()) {
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
