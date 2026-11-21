package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;

import java.util.List;
import java.util.Objects;

/**
 * One structure as seen by entity movement collision.
 *
 * <p>The pose maps structure-origin voxel metres into world space. The box list
 * is the immutable merged collider geometry in unscaled voxel coordinates;
 * {@code parts} carries the exact fractional geometry of cells whose blocks used
 * a partial vanilla shape or a custom collision hook. Both lists are unscaled
 * voxels and must be scaled by {@code blockScale} after transforming by
 * {@code pose}. {@code worldBounds} is the broad-phase AABB of the structure at
 * this pose. The record is deliberately read-only: entity collision must never
 * mutate a physics body.</p>
 *
 * @param boxes       greedy-merged full-cube boxes, in unscaled voxels
 * @param parts       exact fractional cell parts, in unscaled voxels
 */
public record StructureCollisionBox(
        long bodyId,
        Transform3d pose,
        List<VoxelBox> boxes,
        double blockScale,
        Aabb worldBounds,
        List<VoxelCellPart> parts
) {
    public StructureCollisionBox {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(worldBounds, "worldBounds");
        boxes = List.copyOf(boxes);
        parts = parts == null ? List.of() : List.copyOf(parts);
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive and finite: " + blockScale);
        }
    }

    /** Coarse-only constructor retained for existing callers and tests. */
    public StructureCollisionBox(long bodyId, Transform3d pose, List<VoxelBox> boxes,
                                 double blockScale, Aabb worldBounds) {
        this(bodyId, pose, boxes, blockScale, worldBounds, List.of());
    }

    public boolean isEmpty() {
        return boxes.isEmpty() && parts.isEmpty();
    }

    /** Whether this structure has fractional geometry in addition to merged boxes. */
    public boolean hasExactParts() {
        return !parts.isEmpty();
    }
}
