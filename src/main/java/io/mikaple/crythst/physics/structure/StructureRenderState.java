package io.mikaple.crythst.physics.structure;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import org.joml.Vector3d;

/**
 * Immutable, render-thread-safe view of a structure. All heavy data is shared
 * (collider records are immutable); only the pose is sampled.
 */
public record StructureRenderState(
        long bodyId,
        Transform3d pose,
        VoxelColliderData collider,
        Vector3d centerOfMassOffset,
        double blockScale
) {
    public StructureRenderState {
        centerOfMassOffset = new Vector3d(centerOfMassOffset);
    }

    @Override
    public Vector3d centerOfMassOffset() {
        return new Vector3d(centerOfMassOffset);
    }

    /**
     * The internal rigid-body pose. The collider and its
     * {@link VoxelColliderData#localBounds()} are centred on the centre of
     * mass, so bounds and collision queries must use this pose rather than the
     * structure-origin {@link #pose()}.
     */
    public Transform3d bodyPose() {
        final Vector3d bodyPosition = new Vector3d(pose.position())
                .add(new Vector3d(centerOfMassOffset).rotate(pose.rotation()));
        return Transform3d.of(bodyPosition, pose.rotation());
    }

    /**
     * World-space oriented bounds of the collider. This is the same transform
     * the physics shape uses: body pose plus COM-centred collider-local boxes.
     */
    public OrientedBox bounds() {
        return OrientedBox.fromAabb(collider.localBounds(), bodyPose());
    }

    public Aabb aabb() {
        return bounds().toAabb();
    }
}
