package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.integration.minecraft.collision.StructureCollisionBox;
import io.mikaple.crythst.integration.minecraft.collision.StructureCollisionSource;
import io.mikaple.crythst.integration.minecraft.entity.EntityStructureSolver;
import io.mikaple.crythst.math.Aabb;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3d;

/**
 * Checks whether a normal-world block placement would overlap a physics
 * structure.
 *
 * <p>Vanilla only consults the real block grid, where a physics structure is
 * air. Without this guard a player can place a real block inside (or through)
 * a structure that happens to occupy the target cell. The check uses the exact
 * structure collider geometry, including rotated/scaled poses and partial-block
 * parts, and is shared by the server authority and the client prediction
 * mixin.</p>
 */
public final class StructurePlacementOcclusion {
    private StructurePlacementOcclusion() {
    }

    /**
     * @param source structures in the relevant dimension, or {@code null}
     * @param level  real level used to resolve the replacement target
     * @param hit    vanilla block hit for the placement
     * @return {@code true} when the placement cell is occupied by a structure
     */
    public static boolean obstructed(StructureCollisionSource source, Level level,
                                     BlockHitResult hit) {
        if (source == null || level == null || hit == null) {
            return false;
        }
        final BlockPos clicked = hit.getBlockPos();
        final BlockState clickedState = level.getBlockState(clicked);
        final boolean replaceClicked = clickedState.isAir()
                || clickedState.canBeReplaced();
        final BlockPos target = replaceClicked
                ? clicked
                : clicked.relative(hit.getDirection());
        final Aabb cell = new Aabb(
                new Vector3d(target.getX(), target.getY(), target.getZ()),
                new Vector3d(target.getX() + 1.0, target.getY() + 1.0, target.getZ() + 1.0));
        for (StructureCollisionBox structure : source.candidates(cell)) {
            if (structure.isEmpty()) {
                continue;
            }
            final EntityStructureSolver.Hit overlap = EntityStructureSolver.resolve(
                    cell, null, structure.pose(), structure.boxes(), structure.parts(),
                    structure.blockScale(),
                    EntityStructureSolver.Policy.legacy(0.0, 4096));
            if (overlap != null && overlap.penetration() > 1.0e-4) {
                return true;
            }
        }
        return false;
    }
}
