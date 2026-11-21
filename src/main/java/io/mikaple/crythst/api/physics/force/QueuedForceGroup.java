package io.mikaple.crythst.api.physics.force;

import io.mikaple.crythst.api.physics.RigidBodyHandle;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Mutable force/torque accumulator for one {@link ForceGroup} on one structure.
 *
 * <p>Instances are owned by Crythst and handed out from
 * {@code StructurePhysicsContext#forceGroup(ForceGroup)} during an actor
 * callback. Do not retain an instance across substeps and do not share it
 * between structures: the dispatcher reuses it to avoid allocations.</p>
 *
 * <p><strong>Units and frames:</strong> application points are world-space
 * metres; force is in newtons ({@code N}); torque is in newton-metres
 * ({@code N·m}). At the end of each fixed substep the dispatcher converts the
 * totals to impulses using the substep length ({@code N·s = N * dt} and
 * {@code N·m·s = N·m * dt}) and applies them through
 * {@link RigidBodyHandle#applyLinearAndAngularImpulse(Vector3dc, Vector3dc)}.
 * Callers therefore pass a force, not an impulse, and must not multiply by the
 * time step themselves.</p>
 *
 * <p><strong>Side:</strong> server thread only, inside a structure physics actor
 * callback. <strong>Lifecycle:</strong> reset at the start of every substep,
 * applied and cleared at the end; a reference obtained in one substep is not
 * meaningful in the next.</p>
 */
public final class QueuedForceGroup {
    private final ForceGroup group;
    private final Vector3d force = new Vector3d();
    private final Vector3d torque = new Vector3d();
    private final Vector3d centerOfMass = new Vector3d();
    private final Vector3d relative = new Vector3d();
    private final Vector3d torqueDelta = new Vector3d();
    private final Vector3d impulse = new Vector3d();
    private final Vector3d angularImpulse = new Vector3d();

    /**
     * Creates an empty accumulator for a group.
     *
     * <p>The application centre is the world origin until
     * {@link #reset(Vector3dc)} is called. Crythst's dispatcher always resets
     * with the structure's real centre of mass before invoking actors, so mods
     * normally never construct this type directly.</p>
     *
     * @param group immutable group identity; must not be {@code null}
     */
    public QueuedForceGroup(ForceGroup group) {
        if (group == null) {
            throw new NullPointerException("group");
        }
        this.group = group;
    }

    /**
     * The group this accumulator belongs to.
     *
     * @return the immutable group key
     */
    public ForceGroup group() {
        return group;
    }

    /**
     * Accumulates a force applied at a world-space point.
     *
     * <p>The linear part is added to the total force. The angular part adds
     * {@code (worldPoint - centerOfMassWorld) × force}, so a force through the
     * current centre of mass produces no torque.</p>
     *
     * @param worldPoint     world-space application point in metres
     * @param forceNewtons   force vector in newtons
     * @throws NullPointerException when either argument is {@code null}
     */
    public void applyPointForce(Vector3dc worldPoint, Vector3dc forceNewtons) {
        if (worldPoint == null) {
            throw new NullPointerException("worldPoint");
        }
        if (forceNewtons == null) {
            throw new NullPointerException("forceNewtons");
        }
        force.add(forceNewtons);
        relative.set(worldPoint).sub(centerOfMass);
        torque.add(relative.cross(forceNewtons, torqueDelta));
    }

    /**
     * Accumulates a pure force and a pure torque, both independent of an
     * application point.
     *
     * @param forceNewtons        force vector in newtons
     * @param torqueNewtonMetres  torque vector in {@code N·m}
     * @throws NullPointerException when either argument is {@code null}
     */
    public void applyLinearAndAngularForce(Vector3dc forceNewtons, Vector3dc torqueNewtonMetres) {
        if (forceNewtons == null) {
            throw new NullPointerException("forceNewtons");
        }
        if (torqueNewtonMetres == null) {
            throw new NullPointerException("torqueNewtonMetres");
        }
        force.add(forceNewtons);
        torque.add(torqueNewtonMetres);
    }

    /**
     * Copies the force accumulated so far in the current substep.
     *
     * @param dest destination, overwritten with newtons
     * @return {@code dest}
     */
    public Vector3d accumulatedForce(Vector3d dest) {
        return dest.set(force);
    }

    /**
     * Copies the torque accumulated so far in the current substep.
     *
     * @param dest destination, overwritten with {@code N·m}
     * @return {@code dest}
     */
    public Vector3d accumulatedTorque(Vector3d dest) {
        return dest.set(torque);
    }

    /**
     * Applies the accumulated totals to a body as one impulse pair and clears
     * this accumulator.
     *
     * <p>Called by Crythst once per substep after all actors have run. Mods
     * should not need to call it.</p>
     *
     * @param body              body receiving the impulse; must not be {@code null}
     * @param timeStepSeconds   substep length in seconds, strictly positive
     * @throws NullPointerException     when {@code body} is {@code null}
     * @throws IllegalArgumentException when {@code timeStepSeconds} is not positive
     */
    public void applyTo(RigidBodyHandle body, double timeStepSeconds) {
        if (body == null) {
            throw new NullPointerException("body");
        }
        if (!(timeStepSeconds > 0.0) || !Double.isFinite(timeStepSeconds)) {
            throw new IllegalArgumentException("timeStepSeconds must be finite and positive: "
                    + timeStepSeconds);
        }
        if (force.lengthSquared() == 0.0 && torque.lengthSquared() == 0.0) {
            return;
        }
        impulse.set(force).mul(timeStepSeconds);
        angularImpulse.set(torque).mul(timeStepSeconds);
        body.applyLinearAndAngularImpulse(impulse, angularImpulse);
        clear();
    }

    /**
     * Resets the accumulated force/torque and sets the world-space centre used
     * to convert point forces into torque.
     *
     * <p>Called by Crythst's dispatcher at the start of every substep. Public so
     * a custom dispatcher or a unit test can prepare an accumulator without
     * touching private state.</p>
     *
     * @param centerOfMassWorld world-space centre of mass in metres
     * @throws NullPointerException when {@code centerOfMassWorld} is {@code null}
     */
    public void reset(Vector3dc centerOfMassWorld) {
        if (centerOfMassWorld == null) {
            throw new NullPointerException("centerOfMassWorld");
        }
        force.zero();
        torque.zero();
        centerOfMass.set(centerOfMassWorld);
    }

    /**
     * Converts the accumulated force to an impulse for the given substep.
     *
     * <p>Package-visible so the unit conversion can be tested without a native
     * body. The public result is applied by {@link #applyTo(RigidBodyHandle, double)}.</p>
     *
     * @param timeStepSeconds substep length in seconds
     * @param dest            destination, overwritten with {@code N·s}
     * @return {@code dest}
     */
    Vector3d impulse(double timeStepSeconds, Vector3d dest) {
        return dest.set(force).mul(timeStepSeconds);
    }

    /**
     * Converts the accumulated torque to an angular impulse for the given
     * substep.
     *
     * @param timeStepSeconds substep length in seconds
     * @param dest            destination, overwritten with {@code N·m·s}
     * @return {@code dest}
     */
    Vector3d angularImpulse(double timeStepSeconds, Vector3d dest) {
        return dest.set(torque).mul(timeStepSeconds);
    }

    private void clear() {
        force.zero();
        torque.zero();
    }
}
