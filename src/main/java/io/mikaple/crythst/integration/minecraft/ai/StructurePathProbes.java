package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.math.Aabb;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Builds small, non-loading structure probes for navigation helpers.
 */
public final class StructurePathProbes {
    private static final int MIN_HORIZONTAL_RADIUS = 16;
    private static final int VERTICAL_MARGIN = 64;

    private StructurePathProbes() {
    }

    /** A probe around one block position, or {@code null} when no structures are near. */
    public static @Nullable StructurePathProbe near(Level level, BlockPos center, int radius) {
        return near(StructurePathSources.forLevel(level), level, center, radius);
    }

    /** A probe using an already-resolved source (for example from a mob cache). */
    public static @Nullable StructurePathProbe near(StructurePathSource source, Level level,
                                                    BlockPos center, int radius) {
        if (level == null || center == null) {
            return null;
        }
        final int horizontal = Math.max(MIN_HORIZONTAL_RADIUS, radius);
        final Aabb region = new Aabb(
                new Vector3d(center.getX() - horizontal,
                        level.getMinY() - VERTICAL_MARGIN,
                        center.getZ() - horizontal),
                new Vector3d(center.getX() + horizontal + 1.0,
                        level.getMaxY() + VERTICAL_MARGIN + 1.0,
                        center.getZ() + horizontal + 1.0));
        return forRegion(source, level, region);
    }

    /** A probe around a world position, or {@code null} when no structures are near. */
    public static @Nullable StructurePathProbe near(Level level, Vec3 center, int radius) {
        if (center == null) {
            return null;
        }
        return near(level, BlockPos.containing(center.x, center.y, center.z), radius);
    }

    /** A probe for an explicit world region, or {@code null} when empty. */
    public static @Nullable StructurePathProbe forRegion(Level level, Aabb region) {
        return forRegion(StructurePathSources.forLevel(level), level, region);
    }

    /** A probe for an explicit region using an already-resolved source. */
    public static @Nullable StructurePathProbe forRegion(StructurePathSource source, Level level,
                                                         Aabb region) {
        if (level == null || region == null) {
            return null;
        }
        final StructurePathIndex index = StructurePathIndex.build(
                source == null ? StructurePathSource.empty() : source, region);
        if (!index.hasStructures()) {
            return null;
        }
        return StructurePathProbe.of(level, index);
    }
}
