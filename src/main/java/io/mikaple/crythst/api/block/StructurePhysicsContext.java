package io.mikaple.crythst.api.block;

import io.mikaple.crythst.api.physics.MassData;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.QueuedForceGroup;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;

/**
 * Read-only, per-substep view of a structure passed to
 * {@link BlockEntityStructureActor}.
 *
 * <p>The context is created and reused by Crythst. It is valid only for the
 * duration of one {@link BlockEntityStructureActor#structurePhysicsTick} call;
 * do not cache it, its vectors, its body handle or any force group across
 * substeps.</p>
 *
 * <p><strong>Side:</strong> server thread only. <strong>Frames and units:</strong>
 * poses and points are world-space metres; mass is kilograms; force groups use
 * newtons and {@code N·m} (see {@link QueuedForceGroup}).</p>
 */
public interface StructurePhysicsContext {

    /**
     * The live structure being simulated.
     *
     * <p>Its {@link PhysicsStructure#pose()} is the structure-origin pose; the
     * internal rigid body origin is the centre of mass and is exposed through
     * {@link #body()}.</p>
     *
     * @return the structure, never {@code null} while the callback runs
     */
    PhysicsStructure structure();

    /**
     * The real dimension that owns this structure, not the auxiliary ticking
     * level used for block logic.
     *
     * <p>Use this for world lookups, sounds, particles and neighbour logic. The
     * block entity receiving the callback lives in the auxiliary level, so its
     * own {@code getBlockPos()} is <em>not</em> a position in this level; map
     * structure-local coordinates through {@link #structurePose()} first.</p>
     *
     * @return the host server level
     */
    ServerLevel level();

    /**
     * Snapshot of the structure-origin pose for this substep, in world metres.
     *
     * <p>This is not the physics body pose: the body origin is the centre of
     * mass, which generally differs from the structure origin. The returned
     * transform is immutable.</p>
     *
     * @return the structure-origin pose
     */
    Transform3d structurePose();

    /**
     * Stable API handle for the structure's rigid body.
     *
     * <p>Use it for velocities, direct impulses and other body operations. The
     * handle is valid only while the structure is alive; it is refreshed for
     * this structure and remains valid for the duration of the callback.</p>
     *
     * @return the body handle
     */
    RigidBodyHandle body();

    /**
     * Mass and local inertia of the structure at this substep.
     *
     * <p>Mass is in kilograms, the tensor in {@code kg·m²}, and the centre of
     * mass is local to the structure origin in metres. The value is recomputed
     * after a reindex changes the collider.</p>
     *
     * @return immutable mass properties
     */
    MassData massData();

    /**
     * Numeric body id, unique within the level's physics world.
     *
     * @return the body id
     */
    long bodyId();

    /**
     * World-space centre of mass for this substep, in metres.
     *
     * <p>The returned vector is owned by the context and must be treated as
     * read-only. It is the same point used to convert point forces into torque
     * and is normally not the structure origin.</p>
     *
     * @return the world-space centre of mass
     */
    Vector3dc centerOfMassWorld();

    /**
     * Returns the accumulator for a force group, creating it on first use in
     * this structure's lifetime.
     *
     * @param group immutable group key; must not be {@code null}
     * @return the per-structure, per-substep accumulator
     * @throws NullPointerException when {@code group} is {@code null}
     */
    QueuedForceGroup forceGroup(ForceGroup group);

    /**
     * Alias for {@link #forceGroup(ForceGroup)} matching Sable's terminology.
     *
     * @param group immutable group key; must not be {@code null}
     * @return the per-structure, per-substep accumulator
     * @throws NullPointerException when {@code group} is {@code null}
     */
    default QueuedForceGroup getOrCreateForceGroup(ForceGroup group) {
        return forceGroup(group);
    }
}
