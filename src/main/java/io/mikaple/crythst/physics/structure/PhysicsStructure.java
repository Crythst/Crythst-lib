package io.mikaple.crythst.physics.structure;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.InterpolatedPose;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
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
    private final VoxelVolume volume;
    private final io.mikaple.crythst.physics.voxel.VoxelColliderOptions colliderOptions;
    private final double blockScale;
    private final double massScale;
    private final InterpolatedPose renderPose = new InterpolatedPose();

    private volatile VoxelColliderData collider;
    private volatile Vector3d centerOfMassOffset;
    private boolean closed;

    public PhysicsStructure(PhysicsWorld world, StructureDef definition) {
        this.world = world;
        this.volume = definition.volume();
        this.colliderOptions = definition.colliderOptions();
        this.blockScale = definition.colliderOptions().blockScale();
        this.massScale = definition.massScale();
        this.collider = VoxelColliderBuilder.build(definition.volume(), definition.colliderOptions());
        if (collider.mass() <= 0.0) {
            throw new IllegalArgumentException("Structure volume contains no solid blocks");
        }
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
        this.renderPose.reset(pose());
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

    public Transform3d interpolatedPose(double alpha) {
        return renderPose.sample(alpha);
    }

    public void setPose(Transform3d structurePose) {
        body.setTransform(bodyTransformFor(structurePose));
        body.activate();
        renderPose.push(structurePose);
    }

    public void teleport(Transform3d structurePose) {
        body.teleport(bodyTransformFor(structurePose));
        renderPose.reset(structurePose);
    }

    /**
     * Copies the body pose into the render interpolation buffer. Call once per
     * fixed step (or once per server tick) after simulation.
     */
    public void syncRenderPose() {
        renderPose.push(pose());
    }

    public OrientedBox obb() {
        return body.obb();
    }

    public Aabb aabb() {
        return body.aabb();
    }

    /**
     * Render-thread-safe snapshot. Does not touch native physics state.
     */
    public StructureRenderState renderState(double alpha) {
        return new StructureRenderState(body.id(), interpolatedPose(alpha),
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

        final VoxelColliderData newCollider = VoxelColliderBuilder.build(newVolume, colliderOptions);
        if (newCollider.mass() <= 0.0) {
            throw new IllegalArgumentException("New volume contains no solid blocks");
        }

        this.collider = newCollider;
        this.centerOfMassOffset = newCollider.centerOfMass().mul(blockScale);
        final double newMass = newCollider.mass() * massScale;
        body.setShape(newCollider.toCompoundShape(), body.type() == BodyType.DYNAMIC ? newMass : 0.0);

        final Vector3d newBodyPosition = new Vector3d(structurePosition)
                .add(new Vector3d(centerOfMassOffset).rotate(oldBodyPose.rotation()));
        body.setTransform(Transform3d.of(newBodyPosition, oldBodyPose.rotation()));
        body.activate();
        syncRenderPose();
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
