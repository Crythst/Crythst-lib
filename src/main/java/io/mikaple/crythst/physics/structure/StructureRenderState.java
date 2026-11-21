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

    public OrientedBox bounds() {
        return OrientedBox.fromAabb(collider.localBounds(), pose);
    }

    public Aabb aabb() {
        return bounds().toAabb();
    }
}
