package io.mikaple.crythst.physics.structure;

import io.mikaple.crythst.api.block.StructureBlockHooks;
import io.mikaple.crythst.api.block.StructureBlockStateSnapshot;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.InterpolatedPose;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.tick.PoseTimeline;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One rigid moving block structure.
 *
 * <p>The block grid is collapsed into merged boxes, their combined centre of
 * mass becomes the body origin. Bullet derives the rigid body's rotational
 * inertia from the compound collision shape, while {@link VoxelColliderData}
 * exposes the same distribution as a full 3x3 tensor for analysis and custom
 * backends. The structure keeps a render-time {@link InterpolatedPose} so a
 * tilted or tumbling structure can be drawn smoothly between fixed steps.</p>
 */
public final class PhysicsStructure implements AutoCloseable {
    private final PhysicsWorld world;
    private final BodyHandle body;
    private VoxelVolume volume;
    private final io.mikaple.crythst.physics.voxel.VoxelColliderOptions colliderOptions;
    private final double blockScale;
    private final double massScale;
    private final InterpolatedPose renderPose = new InterpolatedPose();
    /**
     * Timestamped render timeline used by renderers. The legacy
     * {@link InterpolatedPose} above is kept for the public alpha API and for
     * diagnostics; render sampling is driven by wall time because server-tick
     * publishes and the client partial tick are not phase-locked.
     */
    private final PoseTimeline renderTimeline = new PoseTimeline();

    private volatile VoxelColliderData collider;
    private volatile Vector3d centerOfMassOffset;
    /** Only allocated when a block-change listener exists for this volume. */
    private volatile StructureBlockStateSnapshot blockStateSnapshot;
    private boolean closed;

    public PhysicsStructure(PhysicsWorld world, StructureDef definition) {
        this.world = world;
        this.volume = definition.volume();
        this.colliderOptions = definition.colliderOptions();
        this.blockScale = definition.colliderOptions().blockScale();
        this.massScale = definition.massScale();
        this.collider = VoxelColliderBuilder.build(definition.volume(), definition.colliderOptions(),
                StructureBlockHooks.colliderHooks(definition.volume()));
        if (collider.mass() <= 0.0 || collider.isEmpty()) {
            throw new IllegalArgumentException("Structure volume contains no solid blocks with collision geometry");
        }
        this.blockStateSnapshot = StructureBlockHooks.captureBlockStates(definition.volume());
        this.centerOfMassOffset = collider.centerOfMass().mul(blockScale);

        final Transform3d bodyTransform = bodyTransformFor(definition.pose());
        final BodyDef.Builder builder = BodyDef.builder(collider.toCompoundShape(), definition.type())
                .transform(bodyTransform)
                .material(definition.material())
                .filter(definition.filter())
                .damping(definition.linearDamping(), definition.angularDamping())
                .ccd(definition.ccd())
                .userData(definition.userData());

        if (definition.type() == BodyType.DYNAMIC) {
            builder.mass(collider.mass() * massScale);
        }

        this.body = world.createBody(builder.build());
        this.body.setUserData(this);
        final Transform3d initialPose = pose();
        this.renderPose.reset(initialPose);
        this.renderTimeline.reset(initialPose, System.nanoTime());
    }

    private Transform3d bodyTransformFor(Transform3d structurePose) {
        return Transform3d.of(structurePose.transformPoint(centerOfMassOffset), structurePose.rotation());
    }

    public PhysicsWorld world() {
        return world;
    }

    public BodyHandle body() {
        return body;
    }

    public VoxelColliderData collider() {
        return collider;
    }

    public VoxelVolume volume() {
        return volume;
    }

    public double blockScale() {
        return blockScale;
    }

    public double massScale() {
        return massScale;
    }

    /**
     * Current structure pose (not the internal centre-of-mass body pose).
     */
    public Transform3d pose() {
        final Transform3d bodyPose = body.transform();
        final Vector3d structurePosition = new Vector3d(bodyPose.position())
                .sub(new Vector3d(centerOfMassOffset).rotate(bodyPose.rotation()));
        return Transform3d.of(structurePosition, bodyPose.rotation());
    }

    /**
     * Samples the legacy alpha-based render pose between the last two
     * published server ticks.
     *
     * <p>This method is retained for API compatibility and for deterministic
     * tests. Renderers should use {@link #interpolatedPoseAt(long)} or
     * {@link #renderState(double)}: the server tick boundary is not
     * phase-locked to the client partial tick, so sampling a server-tick pose
     * pair with the client alpha can jump whenever a server tick lands inside
     * a client tick.</p>
     *
     * <p>{@code alpha} is expected to be Minecraft's tick partial tick
     * ({@code DeltaTracker#getGameTimeDeltaPartialTick}). The physics clock's
     * own accumulator is intentionally <em>not</em> used here: a server tick
     * may run several fixed substeps in a burst, so its remainder does not
     * describe how far the render clock has advanced between ticks.</p>
     */
    public Transform3d interpolatedPose(double alpha) {
        return renderPose.sample(alpha);
    }

    /**
     * Samples the timestamped render timeline at {@code nowNanos}. The newest
     * pose is delayed by one smoothed publish interval, so motion is smooth at
     * frame rate regardless of server/client tick phase.
     */
    public Transform3d interpolatedPoseAt(long nowNanos) {
        return renderTimeline.sample(nowNanos);
    }

    public void setPose(Transform3d structurePose) {
        body.setTransform(bodyTransformFor(structurePose));
        body.activate();
        // A direct pose set is a discontinuity, not a tick-to-tick movement.
        renderPose.reset(structurePose);
        renderTimeline.reset(structurePose, System.nanoTime());
    }

    public void teleport(Transform3d structurePose) {
        body.teleport(bodyTransformFor(structurePose));
        renderPose.reset(structurePose);
        renderTimeline.reset(structurePose, System.nanoTime());
    }

    /**
     * Copies the body pose into the render interpolation buffers. Call exactly
     * once per server tick, after simulation, so each timestamp marks the end
     * of that tick's substep batch. Do not call once per physics substep:
     * substeps run in a burst inside one server tick, and the renderer would
     * then see every substep as a separate tick.
     */
    public void syncRenderPose() {
        final Transform3d current = pose();
        renderPose.push(current);
        renderTimeline.push(current, System.nanoTime());
    }

    /**
     * Translation distance covered by the current render interpolation pair.
     * Diagnostic only; used by {@code /crythst timing}.
     */
    public double renderPoseTranslationDelta() {
        return renderPose.previous().position().distance(renderPose.current().position());
    }

    /**
     * Rotation angle in radians covered by the current render interpolation
     * pair. Diagnostic only; used by {@code /crythst timing}.
     */
    public double renderPoseAngleDelta() {
        final Quaterniond previous = renderPose.previous().rotation();
        final Quaterniond current = renderPose.current().rotation();
        final double dot = Math.abs(previous.x() * current.x()
                + previous.y() * current.y()
                + previous.z() * current.z()
                + previous.w() * current.w());
        return 2.0 * Math.acos(Math.min(1.0, dot));
    }

    /** One-tick render delay currently used by the timestamped timeline. */
    public long renderTimelineDelayNanos() {
        return renderTimeline.delayNanos();
    }

    /** Number of render-timeline teleport/split/stale-gap snaps so far. */
    public long renderTimelineSnapCount() {
        return renderTimeline.snapCount();
    }

    public OrientedBox obb() {
        return body.obb();
    }

    public Aabb aabb() {
        return body.aabb();
    }

    /**
     * Render-thread-safe snapshot. Does not touch native physics state.
     *
     * <p>The {@code alpha} argument is retained for source compatibility but is
     * no longer used to sample the pose. Physics poses are published on the
     * server tick boundary, which is not phase-locked to the client partial
     * tick; callers therefore get the timestamped frame-time sample instead.
     * Use {@link #renderStateAt(long)} when several structures must be sampled
     * at exactly the same frame time.</p>
     */
    public StructureRenderState renderState(double alpha) {
        return renderStateAt(System.nanoTime());
    }

    /**
     * Render-thread-safe snapshot sampled at an explicit monotonic frame time.
     */
    public StructureRenderState renderStateAt(long nowNanos) {
        return new StructureRenderState(body.id(), renderTimeline.sample(nowNanos),
                collider, centerOfMassOffset, blockScale);
    }

    /**
     * World-space centre of a block addressed in structure-local voxel
     * coordinates.
     */
    public Vector3d blockCenter(Vector3d dest, int x, int y, int z) {
        final Vector3d local = new Vector3d(
                (x + 0.5) * blockScale,
                (y + 0.5) * blockScale,
                (z + 0.5) * blockScale
        ).sub(centerOfMassOffset);
        return body.transform().transformPoint(local, dest);
    }

    public Vector3d blockCenter(int x, int y, int z) {
        return blockCenter(new Vector3d(), x, y, z);
    }

    /**
     * Converts a world-space point into structure-local voxel coordinates.
     */
    public Vector3d worldToVoxel(Vector3d dest, Vector3dc worldPoint) {
        final Vector3d bodyLocal = body.transform().inverseTransformPoint(worldPoint);
        return dest.set(bodyLocal)
                .add(centerOfMassOffset)
                .mul(1.0 / blockScale)
                .sub(0.5, 0.5, 0.5);
    }

    public Vector3d velocityAt(Vector3dc worldPoint) {
        final Transform3d bodyPose = body.transform();
        final Vector3d relative = new Vector3d(worldPoint).sub(bodyPose.position());
        return new Vector3d(body.angularVelocity()).cross(relative).add(body.linearVelocity());
    }

    /**
     * Rebuilds the collider after blocks changed. The current world pose is
     * preserved even when the centre of mass shifts.
     */
    public void reindex(VoxelVolume newVolume) {
        final Transform3d oldBodyPose = body.transform();
        final Vector3d structurePosition = new Vector3d(oldBodyPose.position())
                .sub(new Vector3d(centerOfMassOffset).rotate(oldBodyPose.rotation()));

        final StructureBlockStateSnapshot previousStates = this.blockStateSnapshot;
        final VoxelColliderData newCollider = VoxelColliderBuilder.build(newVolume, colliderOptions,
                StructureBlockHooks.colliderHooks(newVolume));
        if (newCollider.mass() <= 0.0 || newCollider.isEmpty()) {
            throw new IllegalArgumentException("New volume contains no solid blocks with collision geometry");
        }

        this.collider = newCollider;
        this.centerOfMassOffset = newCollider.centerOfMass().mul(blockScale);
        // Keep volume() in sync with the new grid. Snapshot producers and
        // auxiliary level refreshes read this field after reindexing.
        this.volume = newVolume;
        final double newMass = newCollider.mass() * massScale;
        body.setShape(newCollider.toCompoundShape(), body.type() == BodyType.DYNAMIC ? newMass : 0.0);

        final Vector3d newBodyPosition = new Vector3d(structurePosition)
                .add(new Vector3d(centerOfMassOffset).rotate(oldBodyPose.rotation()));
        body.setTransform(Transform3d.of(newBodyPosition, oldBodyPose.rotation()));
        body.activate();
        syncRenderPose();
        // Reindexing can move the origin when the centre of mass changes. The
        // block world pose is preserved, but the structure pose itself may have
        // shifted; do not interpolate across that re-anchor.
        renderTimeline.reset(pose(), System.nanoTime());
        StructureBlockHooks.fireBlockChanges(this, previousStates, newVolume);
        this.blockStateSnapshot = StructureBlockHooks.captureBlockStates(newVolume);
    }

    public void setMaterial(PhysicsMaterial material) {
        body.setMaterial(material);
    }

    public void setKinematic(boolean kinematic) {
        body.setType(kinematic ? BodyType.KINEMATIC : BodyType.DYNAMIC);
    }

    public void applyCentralImpulse(Vector3dc impulse) {
        body.applyCentralImpulse(impulse);
    }

    public void applyImpulseAt(Vector3dc impulse, Vector3dc worldPoint) {
        body.applyImpulseAtPoint(impulse, worldPoint);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        world.removeBody(body);
    }

    public boolean closed() {
        return closed;
    }
}
