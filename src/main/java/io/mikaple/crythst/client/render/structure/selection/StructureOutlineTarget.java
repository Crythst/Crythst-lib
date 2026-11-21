package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Neutral contract between the structure selection layer and the vanilla
 * block-outline path.
 *
 * <p>The outline renderer only needs to know which voxel belongs to which
 * structure, where that structure is, how large a voxel is and which block
 * state the voxel carries. Keeping that in one small record lets the selection
 * code evolve without coupling the outline mixin to a particular hit-result
 * implementation.</p>
 *
 * <p>The current adapter is
 * {@link #from(StructureBlockHitResult)}. Any future structure-aware hit only
 * has to construct this record (or add another adapter) with the same
 * pose/voxel/scale data.</p>
 *
 * @param bodyId     physics body id of the structure
 * @param pose       structure-origin pose (not the centre-of-mass body pose)
 * @param voxelX     structure-local voxel coordinate
 * @param voxelY     structure-local voxel coordinate
 * @param voxelZ     structure-local voxel coordinate
 * @param blockScale metres per voxel
 * @param state      visual block state of the voxel, or {@code null} if unknown
 * @param worldHit   world-space ray hit point, for diagnostics
 */
public record StructureOutlineTarget(
        long bodyId,
        Transform3d pose,
        int voxelX,
        int voxelY,
        int voxelZ,
        double blockScale,
        BlockState state,
        Vector3d worldHit
) {
    public StructureOutlineTarget {
        if (pose == null) {
            throw new IllegalArgumentException("pose must not be null");
        }
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive and finite: " + blockScale);
        }
        worldHit = worldHit == null ? new Vector3d() : new Vector3d(worldHit);
    }

    /**
     * Adapter for the client selection result.
     */
    public static StructureOutlineTarget from(StructureBlockHitResult hit) {
        return new StructureOutlineTarget(
                hit.bodyId(),
                hit.structurePose(),
                hit.voxelX(),
                hit.voxelY(),
                hit.voxelZ(),
                hit.blockScale(),
                hit.blockState(),
                hit.worldHit()
        );
    }
}
