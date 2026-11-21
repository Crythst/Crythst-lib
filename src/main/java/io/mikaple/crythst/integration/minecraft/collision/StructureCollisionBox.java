package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelBox;

import java.util.List;
import java.util.Objects;

/**
 * One structure as seen by entity movement collision.
 *
 * <p>The pose maps structure-origin voxel metres into world space and the box
 * list is the immutable merged collider geometry in unscaled voxel
 * coordinates. {@code worldBounds} is the broad-phase AABB of the structure at
 * this pose. The record is deliberately read-only: entity collision must never
 * mutate a physics body.</p>
 */
public record StructureCollisionBox(
        long bodyId,
        Transform3d pose,
        List<VoxelBox> boxes,
        double blockScale,
        Aabb worldBounds
) {
    public StructureCollisionBox {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(worldBounds, "worldBounds");
        boxes = List.copyOf(boxes);
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive and finite: " + blockScale);
        }
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }
}
