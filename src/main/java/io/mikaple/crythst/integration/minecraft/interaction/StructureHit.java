package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3d;

/**
 * A physics ray hit against a block structure, already resolved to the
 * structure-local voxel that should be mined or built on.
 *
 * @param structure the hit structure
 * @param rayHit    the raw physics ray hit (world point, normal, distance)
 * @param voxelX    structure-local voxel X coordinate
 * @param voxelY    structure-local voxel Y coordinate
 * @param voxelZ    structure-local voxel Z coordinate
 * @param face      structure-local face normal; the adjacent cell for placing
 *                  is {@code voxel + face.getStep()}
 */
public record StructureHit(
        PhysicsStructure structure,
        RayHit rayHit,
        int voxelX,
        int voxelY,
        int voxelZ,
        Direction face
) {
    public Vector3d point() {
        return rayHit.point();
    }

    public Vector3d normal() {
        return rayHit.normal();
    }

    public double distance() {
        return rayHit.distance();
    }

    public int adjacentX() {
        return voxelX + face.getStepX();
    }

    public int adjacentY() {
        return voxelY + face.getStepY();
    }

    public int adjacentZ() {
        return voxelZ + face.getStepZ();
    }

    /**
     * World-space cardinal face corresponding to {@link #face()}. Placement
     * uses this for both the synthetic click and the placement-state rules, so
     * a rotated structure still behaves like the block face the player sees.
     */
    public Direction worldFace() {
        return StructurePlacementMath.worldFace(structure.pose(), face);
    }

    /**
     * Synthetic vanilla click for placement-state resolution.
     */
    public BlockHitResult clickResult() {
        return StructurePlacementMath.blockHit(point(), worldFace());
    }
}
