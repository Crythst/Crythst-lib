package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.util.Validate;

/**
 * Options for {@link VoxelColliderBuilder}.
 *
 * @param greedyMerge            merge adjacent cells into larger boxes
 * @param mergeDifferentMaterials allow merging cells whose material or mass
 *                                differs (the first cell's properties win)
 * @param blockScale             metres per voxel
 * @param maxCells               safety limit on the number of visited cells
 */
public record VoxelColliderOptions(
        boolean greedyMerge,
        boolean mergeDifferentMaterials,
        double blockScale,
        int maxCells
) {
    public static final VoxelColliderOptions DEFAULT =
            new VoxelColliderOptions(true, false, 1.0, 4_000_000);

    public VoxelColliderOptions {
        blockScale = Validate.positive(blockScale, "blockScale");
        Validate.atLeast(maxCells, 1, "maxCells");
    }

    public static VoxelColliderOptions defaults() {
        return DEFAULT;
    }

    public VoxelColliderOptions withBlockScale(double scale) {
        return new VoxelColliderOptions(greedyMerge, mergeDifferentMaterials, scale, maxCells);
    }

    public VoxelColliderOptions withGreedyMerge(boolean enabled) {
        return new VoxelColliderOptions(enabled, mergeDifferentMaterials, blockScale, maxCells);
    }

    public VoxelColliderOptions withMergedMaterials(boolean enabled) {
        return new VoxelColliderOptions(greedyMerge, enabled, blockScale, maxCells);
    }
}
