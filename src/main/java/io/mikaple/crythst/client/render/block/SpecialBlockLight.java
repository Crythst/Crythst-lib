package io.mikaple.crythst.client.render.block;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Light sampling for specially rendered blocks.
 *
 * <p>{@link LightCoordsUtil#getLightCoords} samples the light stored at the
 * block's own position. For an opaque, full-cube block that position stores
 * zero because light propagation is stopped at the block boundary. Vanilla
 * samples the neighbouring air face instead, so a solid block rendered without
 * this correction is almost black.</p>
 *
 * <p>The helper takes the component-wise maximum of the block's own light and
 * its six neighbours. This mirrors the correction used by EnderTech's
 * Enderite block renderer. It is only applied to solid-render blocks; for
 * non-solid blocks the block's own sample is already meaningful.</p>
 */
public final class SpecialBlockLight {
    private static final Direction[] DIRECTIONS = Direction.values();

    private SpecialBlockLight() {
    }

    /**
     * Returns packed light coordinates suitable for a special block renderer.
     *
     * @param level the client level
     * @param pos   the block position
     * @param state the block state at {@code pos}
     */
    public static int getLightCoords(ClientLevel level, BlockPos pos, BlockState state) {
        final int own = LightCoordsUtil.getLightCoords(level, pos);
        if (!state.isSolidRender()) {
            return own;
        }

        int light = own;
        for (Direction direction : DIRECTIONS) {
            light = LightCoordsUtil.max(light,
                    LightCoordsUtil.getLightCoords(level, pos.relative(direction)));
        }
        return light;
    }

    /**
     * Applies the six-neighbour maximum to an already sampled light value.
     */
    public static int withNeighborMax(ClientLevel level, BlockPos pos, int lightCoords) {
        int light = lightCoords;
        for (Direction direction : DIRECTIONS) {
            light = LightCoordsUtil.max(light,
                    LightCoordsUtil.getLightCoords(level, pos.relative(direction)));
        }
        return light;
    }
}
