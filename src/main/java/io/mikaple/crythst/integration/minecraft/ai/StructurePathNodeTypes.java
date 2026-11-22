package io.mikaple.crythst.integration.minecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Access to vanilla's node classification without duplicating its block rules.
 *
 * <p>{@code WalkNodeEvaluator.getPathTypeFromState} is protected. A tiny
 * subclass exposes it so the pathfinding context mixin and tests classify
 * structure blocks with exactly the same tags/logic as vanilla (water, lava,
 * fire, cactus, fences, walls, doors, slabs, leaves, ...).</p>
 */
public final class StructurePathNodeTypes {
    private StructurePathNodeTypes() {
    }

    public static PathType pathType(BlockGetter level, BlockPos pos) {
        return Access.pathType(level, pos);
    }

    private static final class Access extends WalkNodeEvaluator {
        private static PathType pathType(BlockGetter level, BlockPos pos) {
            return getPathTypeFromState(level, pos);
        }
    }
}
