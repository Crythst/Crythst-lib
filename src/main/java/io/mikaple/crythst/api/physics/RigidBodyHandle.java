package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Stable handle for one rigid body in a Crythst physics world.
 *
 * <p>A handle normally wraps a {@link PhysicsStructure}, which gives it a
 * structure pose, a collider-derived mass and a world-space centre of mass.
 * Loose bodies created directly in the physics world can be wrapped too; their
 * origin is their centre of mass and their inertia is reported as zero because
 * the generic body interface does not expose a per-shape tensor.</p>
 *
 * <p><strong>Threading:</strong> all methods access native physics state and
 * must be called on the server thread that owns the world. The object is a
 * lightweight view, not a snapshot; cache it only while the body is alive and
 * check {@link #isValid()} before use. No method is null-safe except the
 * destructive/query operations documented otherwise.</p>
 *
 * <p><strong>Units and frames:</strong> positions and points are world-space
 * metres; impulses are newton-seconds ({@code N·s}); linear velocity is
 * {@code m/s}; angular velocity is {@code rad/s}; mass is kilograms. The
 * teleport position is the structure origin (not the centre of mass).</p>
 */
public final class RigidBodyHandle {
    private final BodyHandle body;
    private final @Nullable PhysicsStructure structure;

    private RigidBodyHandle(BodyHandle body, @Nullable PhysicsStructure structure) {
        if (body == null) {
            throw new NullPointerException("body");
        }
        this.body = body;
        this.structure = structure;
    }

    /**
     * Wraps a structure body.
     */
    public static RigidBodyHandle of(PhysicsStructure structure) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        return new RigidBodyHandle(structure.body(), structure);
    }

    /**
     * Wraps a loose body. The handle has no structure pose or collider tensor.
     */
    public static RigidBodyHandle of(BodyHandle body) {
        return new RigidBodyHandle(body, null);
    }

    /** Numeric body id, unique within its physics world. */
    public long id() {
        return body.id();
    }

    /**
     * The wrapped structure, or {@code null} for a loose body.
     */
    public @Nullable PhysicsStructure structure() {
        return structure;
    }

    /**
     * @return {@code true} while the body still exists in its world and, for a
     *         structure, has not been closed
     */
    public boolean isValid() {
        return body.inWorld() && (structure == null || !structure.closed());
    }

    /**
     * Applies an impulse at a world-space point.
     *
     * @param point   application point in world metres
     * @param impulse impulse vector in {@code N·s}
     */
    public void applyImpulseAtPoint(Vector3dc point, Vector3dc impulse) {
        body.applyImpulseAtPoint(impulse, point);
    }

    /**
     * Applies a pure linear impulse through the centre of mass.
     *
     * @param impulse impulse vector in {@code N·s}
     */
    public void applyLinearImpulse(Vector3dc impulse) {
        body.applyCentralImpulse(impulse);
    }

    /**
     * Applies a pure angular impulse (change of angular momentum).
     *
     * @param impulse angular impulse in {@code N·m·s}
     */
    public void applyAngularImpulse(Vector3dc impulse) {
        body.applyTorqueImpulse(impulse);
    }

    /**
     * Alias for {@link #applyAngularImpulse(Vector3dc)}.
     *
     * @param torque angular impulse in {@code N·m·s}
     */
    public void applyTorqueImpulse(Vector3dc torque) {
        applyAngularImpulse(torque);
    }

    /**
     * Applies a linear impulse and an angular impulse in one call.
     *
     * @param linear  linear impulse in {@code N·s}
     * @param angular angular impulse in {@code N·m·s}
     */
    public void applyLinearAndAngularImpulse(Vector3dc linear, Vector3dc angular) {
        body.applyCentralImpulse(linear);
        body.applyTorqueImpulse(angular);
    }

    /**
     * Copies the body's world-space linear velocity.
     *
     * @param dest destination, overwritten with {@code m/s}
     * @return {@code dest}
     */
    public Vector3d getLinearVelocity(Vector3d dest) {
        return dest.set(body.linearVelocity());
    }

    /**
     * Copies the body's world-space angular velocity.
     *
     * @param dest destination, overwritten with {@code rad/s}
     * @return {@code dest}
     */
    public Vector3d getAngularVelocity(Vector3d dest) {
        return dest.set(body.angularVelocity());
    }

    /**
     * Computes the linear velocity of the material point currently at
     * {@code worldPoint}, including the angular contribution
     * {@code ω × r}.
     *
     * @param worldPoint world-space point in metres
     * @param dest       destination in {@code m/s}
     * @return {@code dest}
     */
    public Vector3d getVelocityAtPoint(Vector3dc worldPoint, Vector3d dest) {
        final Vector3d relative = new Vector3d(worldPoint).sub(body.transform().position());
        return dest.set(body.angularVelocity()).cross(relative).add(body.linearVelocity());
    }

    /**
     * Adds to the current linear and angular velocities.
     *
     * @param linear  delta linear velocity in {@code m/s}
     * @param angular delta angular velocity in {@code rad/s}
     */
    public void addLinearAndAngularVelocity(Vector3dc linear, Vector3dc angular) {
        body.setLinearVelocity(new Vector3d(body.linearVelocity()).add(linear));
        body.setAngularVelocity(new Vector3d(body.angularVelocity()).add(angular));
    }

    /**
     * Teleports the structure origin (or the body origin for a loose body).
     *
     * <p>This is a discontinuity: render interpolation is reset and no velocity
     * is changed unless the caller changes it explicitly.</p>
     *
     * @param position    new world position in metres
     * @param orientation new world rotation
     */
    public void teleport(Vector3dc position, Quaterniondc orientation) {
        final Transform3d transform = Transform3d.of(position, orientation);
        if (structure != null) {
            structure.teleport(transform);
        } else {
            body.teleport(transform);
        }
    }

    /**
     * Copies the structure/body origin in world metres.
     */
    public Vector3d getPosition(Vector3d dest) {
        return dest.set(getPosition());
    }

    /**
     * @return a new vector containing the structure/body origin in world metres
     */
    public Vector3d getPosition() {
        return structure == null ? body.transform().position() : structure.pose().position();
    }

    /**
     * Copies the world rotation.
     */
    public Quaterniond getOrientation(Quaterniond dest) {
        return dest.set(getOrientation());
    }

    /**
     * @return a new quaternion containing the world rotation
     */
    public Quaterniond getOrientation() {
        return structure == null ? body.transform().rotation() : structure.pose().rotation();
    }

    /**
     * Total body mass in kilograms.
     */
    public double getMass() {
        return body.mass();
    }

    /**
     * World-space centre of mass in metres.
     *
     * <p>For a structure this is the pose-transformed collider centre, which is
     * not generally the structure origin. For a loose body it is the body
     * origin.</p>
     */
    public Vector3d getCenterOfMass() {
        if (structure == null) {
            return body.transform().position();
        }
        final Vector3d local = new Vector3d(structure.collider().centerOfMass())
                .mul(structure.blockScale());
        return structure.pose().transformPoint(local);
    }

    /**
     * Mass and inertia in the structure/body local frame.
     *
     * <p>Loose bodies report a zero inertia tensor because their generic shape
     * interface does not expose a distribution. Structures report the full
     * collider tensor scaled by the structure's mass scale.</p>
     */
    public MassData massData() {
        if (structure != null) {
            return MassData.forStructure(structure);
        }
        return new MassData(body.mass(), new Matrix3d().zero(), new Vector3d());
    }
}
