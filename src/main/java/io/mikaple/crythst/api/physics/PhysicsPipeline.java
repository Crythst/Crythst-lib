package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.api.physics.constraint.StructureConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.StructureConstraintHandle;
import io.mikaple.crythst.api.physics.box.StructureBoxDef;
import io.mikaple.crythst.api.physics.box.StructureBoxObject;
import io.mikaple.crythst.api.physics.force.ForceTotals;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Server-side API view of one level's physics pipeline.
 *
 * <p>The pipeline exposes the small set of operations third-party mods need:
 * looking up bodies, applying impulses, changing velocities, teleporting and
 * creating or removing structure-to-structure and structure-to-world joints.
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
     * Low-level physics world behind this pipeline.
     *
     * <p>Intended for advanced integrations and for the native box/rope
     * helpers. Do not call {@code step}/{@code advance} through it: Crythst's
     * fixed-step clock owns simulation timing. Prefer the pipeline operations
     * for bodies and joints.</p>
     */
    public io.mikaple.crythst.physics.world.PhysicsWorld physicsWorld() {
        return levelPhysics.world();
    }

    /**
     * Creates a constraint between two structures.
     *
     * <p>Frames, anchors and axes in {@code config} are interpreted in
     * structure-local metres and converted to each body's local frame using the
     * live structure pose, body pose and centre of mass. The returned handle is
     * a live view; call {@link StructureConstraintHandle#isValid()} before using
     * it after body or structure changes.</p>
     *
     * @param a      structure A; must belong to this pipeline's level
     * @param b      structure B; must belong to this pipeline's level and differ
     *               from {@code a}
     * @param config joint description
     * @return a handle for the new constraint
     * @throws IllegalArgumentException when a structure is closed, belongs to a
     *                                  different world or level, or both
     *                                  arguments name the same body
     */
    public StructureConstraintHandle addConstraint(PhysicsStructure a, PhysicsStructure b,
                                                   StructureConstraintConfiguration config) {
        requireStructure(a, "a");
        requireStructure(b, "b");
        if (a == b || a.body().id() == b.body().id()) {
            throw new IllegalArgumentException("A constraint needs two distinct structures");
        }
        if (config == null) {
            throw new NullPointerException("config");
        }
        final ConstraintDef definition = config.toDefinition(a, b);
        final ConstraintHandle handle = levelPhysics.world().addConstraint(definition);
        return new StructureConstraintHandle(levelPhysics.world(), handle, a, b, config);
    }

    /**
     * Creates a constraint between one structure and the world.
     *
     * <p>The missing structure B is the world, whose pose is the identity. For
     * {@code config}, structure-A frames/anchors/axes are structure-local metres;
     * structure-B frames/anchors/axes are world-space metres.</p>
     *
     * @param a      structure to anchor; must belong to this pipeline's level
     * @param config joint description
     * @return a handle for the new constraint
     * @throws IllegalArgumentException when the structure is closed or belongs
     *                                  to a different world or level
     */
    public StructureConstraintHandle addConstraint(PhysicsStructure a,
                                                   StructureConstraintConfiguration config) {
        requireStructure(a, "a");
        if (config == null) {
            throw new NullPointerException("config");
        }
        final ConstraintDef definition = config.toDefinition(a, null);
        final ConstraintHandle handle = levelPhysics.world().addConstraint(definition);
        return new StructureConstraintHandle(levelPhysics.world(), handle, a, null, config);
    }

    /**
     * Removes a constraint created through this pipeline.
     *
     * @param constraint handle to remove
     * @return {@code true} when the joint was still present and has been removed
     * @throws IllegalArgumentException when the handle belongs to another
     *                                  pipeline world
     */
    public boolean removeConstraint(StructureConstraintHandle constraint) {
        if (constraint == null) {
            throw new NullPointerException("constraint");
        }
        if (!constraint.belongsTo(levelPhysics.world())) {
            throw new IllegalArgumentException("Constraint does not belong to this pipeline");
        }
        if (!constraint.isValid()) {
            return false;
        }
        constraint.remove();
        return true;
    }

    /**
     * Removes every constraint involving a structure.
     *
     * <p>Useful before replacing/rebuilding a body when callers want the
     * removal to happen explicitly rather than relying on backend body removal.
     * Safe to call with a structure that has no constraints.</p>
     *
     * @param structure structure whose joints should be removed
     * @return the number of live constraints removed
     * @throws IllegalArgumentException when the structure belongs to another
     *                                  physics world or level
     */
    public int removeConstraints(PhysicsStructure structure) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (structure.world() != levelPhysics.world()) {
            throw new IllegalArgumentException("structure belongs to a different physics world");
        }
        int removed = 0;
        for (StructureConstraintHandle handle : constraints()) {
            if (handle.isValid() && handle.involves(structure)) {
                handle.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Returns handles for all constraints in the world, including joints created
     * through the raw physics world.
     *
     * <p>The returned list is an immutable snapshot. Structures are matched by
     * body id when available; a constraint on loose bodies has no structure
     * associated with it.</p>
     */
    public List<StructureConstraintHandle> constraints() {
        final Collection<ConstraintHandle> raw = levelPhysics.world().constraints();
        final List<StructureConstraintHandle> handles = new ArrayList<>(raw.size());
        for (ConstraintHandle handle : raw) {
            PhysicsStructure structureA = null;
            PhysicsStructure structureB = null;
            for (PhysicsStructure structure : levelPhysics.structures()) {
                if (structure.closed() || !handle.involves(structure.body())) {
                    continue;
                }
                if (structureA == null) {
                    structureA = structure;
                } else if (structureB == null) {
                    structureB = structure;
                } else {
                    break;
                }
            }
            handles.add(new StructureConstraintHandle(levelPhysics.world(), handle,
                    structureA, structureB));
        }
        return List.copyOf(handles);
    }

    /** Number of constraints in the world. */
    public int constraintCount() {
        return levelPhysics.world().constraintCount();
    }

    /**
     * Creates a per-cell mass tracker for a structure.
     *
     * <p>The tracker is a lightweight read view. Total mass comes from the
     * collider (including custom hooks and the structure mass scale); per-cell
     * masses come from the structure volume.</p>
     *
     * @param structure structure registered with this level
     * @return a mass tracker; server thread only
     * @throws IllegalArgumentException when the structure is closed or belongs
     *                                  to another world/level
     */
    public StructureMassTracker massTracker(PhysicsStructure structure) {
        requireStructure(structure, "structure");
        return StructureMassTracker.of(structure);
    }

    /**
     * Force/torque totals recorded for a structure during the most recent
     * fixed substep.
     *
     * <p>Only forces queued through block-entity actor force groups are
     * recorded. Direct impulses applied through a body handle and the public
     * pre-step event are not part of this snapshot.</p>
     *
     * @param structure structure to query
     * @return an immutable snapshot, never {@code null}
     * @throws IllegalArgumentException when the structure belongs to another
     *                                  world/level
     */
    public ForceTotals forceTotals(PhysicsStructure structure) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (structure.world() != levelPhysics.world()) {
            throw new IllegalArgumentException("structure belongs to a different physics world");
        }
        return levelPhysics.forceTotals(structure);
    }

    /**
     * Force/torque totals for a body id.
     *
     * @return the recorded totals, or {@link ForceTotals#empty(long)} for a
     *         loose body/unknown id
     */
    public ForceTotals forceTotals(long bodyId) {
        for (PhysicsStructure structure : levelPhysics.structures()) {
            if (structure.body().id() == bodyId) {
                return levelPhysics.forceTotals(structure);
            }
        }
        return ForceTotals.empty(bodyId);
    }

    /**
     * Force/torque totals for every registered structure, in creation order.
     */
    public List<ForceTotals> forceTotals() {
        final List<PhysicsStructure> structures = levelPhysics.structures();
        final List<ForceTotals> totals = new ArrayList<>(structures.size());
        for (PhysicsStructure structure : structures) {
            totals.add(levelPhysics.forceTotals(structure));
        }
        return List.copyOf(totals);
    }

    /**
     * Creates a loose box rigid body in this pipeline's world.
     *
     * @param definition box description
     * @return a live box handle
     */
    public StructureBoxObject createBox(StructureBoxDef definition) {
        return StructureBoxObject.create(this, definition);
    }

    /**
     * Wraps an existing loose box body by id.
     *
     * @return the handle, or {@code null} when the id is unknown or names a
     *         structure body
     */
    public @Nullable StructureBoxObject box(long bodyId) {
        return StructureBoxObject.wrap(this, bodyId);
    }

    private void requireStructure(PhysicsStructure structure, String name) {
        if (structure == null) {
            throw new NullPointerException(name);
        }
        if (structure.closed()) {
            throw new IllegalArgumentException(name + " is closed");
        }
        if (structure.world() != levelPhysics.world()) {
            throw new IllegalArgumentException(name + " belongs to a different physics world");
        }
        if (!structure.body().inWorld()) {
            throw new IllegalArgumentException(name + " body is no longer in the world");
        }
        for (PhysicsStructure candidate : levelPhysics.structures()) {
            if (candidate == structure) {
                return;
            }
        }
        throw new IllegalArgumentException(name + " is not registered with this level");
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
