package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side API view of one level's physics pipeline.
 *
 * <p>The pipeline exposes the small set of operations third-party mods need:
 * looking up bodies, applying impulses, changing velocities and teleporting.
 * It intentionally does not expose raw world stepping. The simulation is
 * advanced by Crythst's fixed-step clock from the server tick; a mod that calls
 * {@code step} directly could desynchronise render interpolation, terrain
 * loading and block logic.</p>
 *
 * <p>All methods must be called on the server thread. A pipeline remains valid
 * until its level is unloaded; individual body handles become invalid when
 * their body is removed.</p>
 */
public final class PhysicsPipeline {
    private final LevelPhysics levelPhysics;

    /**
     * Wraps an existing level physics world.
     *
     * <p>Mods normally obtain a pipeline from
     * {@link io.mikaple.crythst.api.CrythstApi#physics(net.minecraft.server.level.ServerLevel)}.
     * Constructing a pipeline does not create or step a world.</p>
     */
    public PhysicsPipeline(LevelPhysics levelPhysics) {
        if (levelPhysics == null) {
            throw new NullPointerException("levelPhysics");
        }
        this.levelPhysics = levelPhysics;
    }

    /** Server level whose world this pipeline drives. */
    public ServerLevel level() {
        return levelPhysics.level();
    }

    /**
     * @return {@code true} when the underlying world is still open
     */
    public boolean isOpen() {
        return !levelPhysics.closed();
    }

    /** Number of loose and structure bodies currently in the world. */
    public int bodyCount() {
        return levelPhysics.world().bodyCount();
    }

    /**
     * Returns handles for all structures in creation order.
     *
     * <p>The returned list is an immutable snapshot; modifying it does not
     * affect the world.</p>
     */
    public List<RigidBodyHandle> structures() {
        final List<PhysicsStructure> structures = levelPhysics.structures();
        final List<RigidBodyHandle> handles = new ArrayList<>(structures.size());
        for (PhysicsStructure structure : structures) {
            handles.add(RigidBodyHandle.of(structure));
        }
        return List.copyOf(handles);
    }

    /**
     * Finds a body by id, preferring a structure body when ids overlap.
     *
     * @return a handle, or {@code null} when no such body exists
     */
    public @Nullable RigidBodyHandle body(long bodyId) {
        for (PhysicsStructure structure : levelPhysics.structures()) {
            if (structure.body().id() == bodyId) {
                return RigidBodyHandle.of(structure);
            }
        }
        final BodyHandle body = levelPhysics.world().body(bodyId);
        return body == null ? null : RigidBodyHandle.of(body);
    }

    /** Whether a body with this id still exists. */
    public boolean containsBody(long bodyId) {
        return body(bodyId) != null;
    }

    /**
     * Applies an impulse at a world-space point.
     *
     * @return {@code false} when the body id no longer exists
     */
    public boolean applyImpulseAtPoint(long bodyId, Vector3dc point, Vector3dc impulse) {
        final RigidBodyHandle handle = body(bodyId);
        if (handle == null) {
            return false;
        }
        handle.applyImpulseAtPoint(point, impulse);
        return true;
    }

    /**
     * Applies a centre-of-mass linear impulse and an angular impulse.
     *
     * @return {@code false} when the body id no longer exists
     */
    public boolean applyLinearAndAngularImpulse(long bodyId, Vector3dc linear, Vector3dc angular) {
        final RigidBodyHandle handle = body(bodyId);
        if (handle == null) {
            return false;
        }
        handle.applyLinearAndAngularImpulse(linear, angular);
        return true;
    }

    /**
     * Teleports a body.
     *
     * @param position    new structure/body origin in world metres
     * @param orientation new world rotation
     * @return {@code false} when the body id no longer exists
     */
    public boolean teleport(long bodyId, Vector3dc position, Quaterniondc orientation) {
        final RigidBodyHandle handle = body(bodyId);
        if (handle == null) {
            return false;
        }
        handle.teleport(position, orientation);
        return true;
    }

    /**
     * Copies a body's world linear velocity, or zero when the body is gone.
     */
    public Vector3d getLinearVelocity(long bodyId, Vector3d dest) {
        final RigidBodyHandle handle = body(bodyId);
        return handle == null ? dest.zero() : handle.getLinearVelocity(dest);
    }

    /**
     * Copies a body's world angular velocity, or zero when the body is gone.
     */
    public Vector3d getAngularVelocity(long bodyId, Vector3d dest) {
        final RigidBodyHandle handle = body(bodyId);
        return handle == null ? dest.zero() : handle.getAngularVelocity(dest);
    }

    /**
     * Adds to a body's linear and angular velocities.
     *
     * @return {@code false} when the body id no longer exists
     */
    public boolean addLinearAndAngularVelocity(long bodyId, Vector3dc linear, Vector3dc angular) {
        final RigidBodyHandle handle = body(bodyId);
        if (handle == null) {
            return false;
        }
        handle.addLinearAndAngularVelocity(linear, angular);
        return true;
    }
}
