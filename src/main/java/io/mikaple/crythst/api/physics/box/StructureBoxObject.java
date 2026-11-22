package io.mikaple.crythst.api.physics.box;

import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Live handle for a loose box rigid body created through a
 * {@link PhysicsPipeline}.
 *
 * <p>Boxes are ordinary Crythst rigid bodies, not structures: they have no
 * voxel grid, no auxiliary block level and no structure pose. Their body origin
 * is the box centre, so {@link #pose()} is also their centre-of-mass pose. Use
 * them for crates, debris, contraption parts or simple dynamic obstacles.</p>
 *
 * <p><strong>Units:</strong> metres, kilograms, {@code N·s}, {@code N·m·s},
 * {@code m/s}, {@code rad/s}. <strong>Threading:</strong> server thread only.
 * The handle remains usable until {@link #remove()} or until the owning world
 * closes.</p>
 */
public final class StructureBoxObject implements AutoCloseable {
    private final PhysicsWorld world;
    private final BodyHandle body;
    private final RigidBodyHandle handle;
    private final Vector3d halfExtents;
    private boolean removed;

    private StructureBoxObject(PhysicsWorld world, BodyHandle body, Vector3dc halfExtents) {
        this.world = world;
        this.body = body;
        this.handle = RigidBodyHandle.of(body);
        this.halfExtents = new Vector3d(halfExtents);
    }

    /**
     * Creates a box in the pipeline's world.
     *
     * @param pipeline owning pipeline; must not be {@code null}
     * @param definition box description; must not be {@code null}
     * @return a live box handle
     */
    public static StructureBoxObject create(PhysicsPipeline pipeline, StructureBoxDef definition) {
        if (pipeline == null) {
            throw new NullPointerException("pipeline");
        }
        if (definition == null) {
            throw new NullPointerException("definition");
        }
        if (!pipeline.isOpen()) {
            throw new IllegalStateException("Pipeline is closed");
        }
        final PhysicsWorld world = pipeline.physicsWorld();
        final BodyDef bodyDef = bodyDefinition(definition);
        final BodyHandle body = world.createBody(bodyDef);
        return new StructureBoxObject(world, body, definition.halfExtents());
    }

    /**
     * Wraps an existing loose body. The body must not be a structure body and
     * must have a box shape for {@link #halfExtents()} to be meaningful.
     *
     * @param pipeline owning pipeline
     * @param bodyId   loose body id
     * @return the handle, or {@code null} when the body does not exist or is a
     *         structure
     */
    public static @Nullable StructureBoxObject wrap(PhysicsPipeline pipeline, long bodyId) {
        if (pipeline == null) {
            throw new NullPointerException("pipeline");
        }
        final BodyHandle body = pipeline.physicsWorld().body(bodyId);
        if (body == null || body.userData() instanceof io.mikaple.crythst.physics.structure.PhysicsStructure) {
            return null;
        }
        return new StructureBoxObject(pipeline.physicsWorld(), body, new Vector3d(0.5, 0.5, 0.5));
    }

    private static BodyDef bodyDefinition(StructureBoxDef definition) {
        final BodyDef.Builder builder = BodyDef.builder(
                        Shapes.halfExtents(definition.halfExtents()), definition.type())
                .transform(definition.pose())
                .material(definition.material())
                .filter(definition.filter())
                .damping(definition.linearDamping(), definition.angularDamping())
                .ccd(definition.ccd())
                .userData(definition.userData());
        if (definition.type() == BodyType.DYNAMIC && definition.mass() > 0.0) {
            builder.mass(definition.mass());
        }
        return builder.build();
    }

    /** Numeric body id, unique in its world. */
    public long id() {
        return body.id();
    }

    /** API rigid-body handle over the box body. */
    public RigidBodyHandle body() {
        return handle;
    }

    /** Box half extents in metres. */
    public Vector3d halfExtents() {
        return new Vector3d(halfExtents);
    }

    /** Current box (and centre-of-mass) pose in world space. */
    public Transform3d pose() {
        return body.transform();
    }

    /** Current world-space centre position in metres. */
    public Vector3d position() {
        return body.transform().position();
    }

    /** Current total mass in kilograms. Static/kinematic boxes report {@code 0}. */
    public double mass() {
        return body.mass();
    }

    /** Whether the box still exists in its world and has not been removed. */
    public boolean isValid() {
        return !removed && !world.closed() && body.inWorld();
    }

    /** Applies a centre-of-mass impulse in {@code N·s}. */
    public void applyImpulse(Vector3dc impulse) {
        body.applyCentralImpulse(impulse);
    }

    /** Applies an impulse at a world-space point in {@code N·s}. */
    public void applyImpulseAt(Vector3dc point, Vector3dc impulse) {
        body.applyImpulseAtPoint(impulse, point);
    }

    /** Applies a pure angular impulse in {@code N·m·s}. */
    public void applyAngularImpulse(Vector3dc impulse) {
        body.applyTorqueImpulse(impulse);
    }

    /** Linear velocity in {@code m/s}. */
    public Vector3d linearVelocity() {
        return body.linearVelocity();
    }

    /** Angular velocity in {@code rad/s}. */
    public Vector3d angularVelocity() {
        return body.angularVelocity();
    }

    /** Sets the world pose; the box centre is the body origin. */
    public void teleport(Transform3d pose) {
        if (pose == null) {
            throw new NullPointerException("pose");
        }
        body.teleport(pose);
    }

    /** Sets the world centre position, preserving rotation. */
    public void teleport(Vector3dc position) {
        teleport(pose().withPosition(position));
    }

    /**
     * Removes the body from its world. Idempotent.
     */
    public void remove() {
        if (removed) {
            return;
        }
        removed = true;
        world.removeBody(body);
    }

    /** Alias for {@link #remove()}. */
    @Override
    public void close() {
        remove();
    }

    @Override
    public String toString() {
        return "StructureBoxObject[id=" + body.id()
                + ", halfExtents=" + halfExtents
                + ", removed=" + removed + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StructureBoxObject box
                && box.world == world && box.body.id() == body.id();
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(world), body.id());
    }
}
