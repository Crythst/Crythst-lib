package io.mikaple.crythst.integration.minecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Structure-aware equivalent of
 * {@code GroundPathNavigation#findSurfacePosition}.
 *
 * <p>Vanilla only inspects the host chunk. When the target block is air (the
 * normal case above a structure) it scans down to world terrain, so a mob
 * standing on a moving platform is asked to path to the ground far below the
 * platform. This helper scans the same column through a
 * {@link StructurePathProbe}: real world blocks still win, but a structure
 * floor is found before the host terrain.</p>
 */
public final class StructurePathSurface {
    private StructurePathSurface() {
    }

    /**
     * Resolves a navigation surface for a world position.
     *
     * @return a surface block position, or {@code null} when no structure
     *         touches this column (the caller should run vanilla logic)
     */
    public static @Nullable BlockPos findSurfacePosition(Level level, BlockPos pos,
                                                         int reachRange) {
        if (level == null || pos == null) {
            return null;
        }
        final StructurePathProbe probe = StructurePathProbes.near(
                level, pos, Math.max(16, reachRange + 1));
        if (probe == null || !probe.index().affectsColumn(pos.getX(), pos.getZ())) {
            return null;
        }
        final BlockPos result = findSurfacePosition(probe, pos,
                StructurePathLimits.MAX_SURFACE_SCAN);
        StructurePathDiagnostics.surfaceQuery(result != null);
        return result;
    }

    /**
     * Pure surface scan over any structure-aware getter. Mirrors vanilla's
     * air-down, then solid-up search, bounded by {@code maxSteps}.
     */
    public static @Nullable BlockPos findSurfacePosition(StructurePathProbe probe,
                                                         BlockPos pos, int maxSteps) {
        if (probe == null || pos == null || maxSteps <= 0) {
            return null;
        }
        final int minY = probe.getMinY();
        final int maxY = probe.getMinY() + probe.getHeight() - 1;
        BlockPos target = pos;
        final BlockState initial = probe.getBlockState(target);
        if (initial.isAir()) {
            BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos(
                    target.getX(), target.getY() - 1, target.getZ());
            int steps = 0;
            while (column.getY() >= minY && probe.getBlockState(column).isAir()
                    && ++steps <= maxSteps) {
                column.setY(column.getY() - 1);
            }
            if (column.getY() >= minY && steps <= maxSteps) {
                return column.above();
            }
            if (steps > maxSteps) {
                return null;
            }

            column.setY(target.getY() + 1);
            steps = 0;
            while (column.getY() <= maxY && probe.getBlockState(column).isAir()
                    && ++steps <= maxSteps) {
                column.setY(column.getY() + 1);
            }
            if (steps > maxSteps) {
                return null;
            }
            target = column.immutable();
        }

        if (!probe.getBlockState(target).isSolid()) {
            return target;
        }

        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos(
                target.getX(), target.getY() + 1, target.getZ());
        int steps = 0;
        while (column.getY() <= maxY && probe.getBlockState(column).isSolid()
                && ++steps <= maxSteps) {
            column.setY(column.getY() + 1);
        }
        return steps > maxSteps ? null : column.immutable();
    }
}
