package io.mikaple.crythst.client.render.light;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pure voxel shading rules shared by the batched structure mesher and tests.
 *
 * <p>The rules intentionally mirror vanilla's chunk mesher where it matters:</p>
 * <ul>
 *   <li>A face is hidden when {@link Block#shouldRenderFace} says the neighbour
 *       covers it. This handles full cubes, slabs, glass and modded shape
 *       occlusion instead of only testing {@code isSolid()}.</li>
 *   <li>Vertex AO uses the classic three-neighbour rule. The two edge
 *       neighbours are {@code side1}/{@code side2}; when both are solid the
 *       corner cannot brighten the vertex, otherwise the number of solid
 *       neighbours maps to four shade levels.</li>
 * </ul>
 */
public final class VoxelLighting {
    /** Shade multiplier indexed by the 0..3 AO level. */
    public static final float[] AO_SHADE = {0.55f, 0.70f, 0.85f, 1.00f};

    private VoxelLighting() {
    }

    /**
     * Returns whether a face of {@code state} is completely hidden by
     * {@code neighbour} in the given direction.
     *
     * <p>The neighbour must be part of the same structure. Passing {@code null}
     * (outside the snapshot) never culls, so the outer shell stays visible.</p>
     */
    public static boolean hidesFace(BlockState state, BlockState neighbour, Direction direction) {
        if (state == null || neighbour == null || neighbour.isAir()) {
            return false;
        }
        return !Block.shouldRenderFace(state, neighbour, direction);
    }

    /**
     * Classic voxel AO level. {@code 3} is fully open and {@code 0} is a dark
     * corner; when both edge neighbours are solid the corner is treated as
     * fully occluded.
     */
    public static int aoLevel(boolean side1, boolean side2, boolean corner) {
        if (side1 && side2) {
            return 0;
        }
        return 3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (corner ? 1 : 0);
    }

    public static float aoShade(int level) {
        if (level <= 0) {
            return AO_SHADE[0];
        }
        if (level >= 3) {
            return AO_SHADE[3];
        }
        return AO_SHADE[level];
    }

    /**
     * Multiplies an ARGB colour by a shade factor, preserving alpha.
     */
    public static int applyShade(int argb, float shade) {
        if (shade >= 1.0f) {
            return argb;
        }
        if (shade <= 0.0f) {
            return argb & 0xFF000000;
        }
        final int alpha = argb & 0xFF000000;
        final int red = clamp255(Math.round(((argb >>> 16) & 0xFF) * shade));
        final int green = clamp255(Math.round(((argb >>> 8) & 0xFF) * shade));
        final int blue = clamp255(Math.round((argb & 0xFF) * shade));
        return alpha | (red << 16) | (green << 8) | blue;
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
