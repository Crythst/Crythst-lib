package io.mikaple.crythst.client.render.block;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable data passed to a {@link SpecialBlockRenderer} for one block in one
 * frame.
 *
 * <p>For an ordinary world block, {@code pos} is the actual block position. For
 * a block inside a rotating physics structure it is a representative world cell
 * chosen for level/light lookups; the exact rotated block centre is supplied
 * separately to the additive {@link SpecialBlockRenderer} structure overload.
 * The pose is exact in both cases.</p>
 *
 * @param pos         immutable block position (representative for structures)
 * @param state       the block state currently stored at {@code pos}
 * @param level       the client level containing the block
 * @param partialTick the current frame's partial tick, for animation
 * @param cameraPos   absolute camera position used by this frame's submission
 * @param distanceSqr squared distance from {@code cameraPos} to the block centre
 */
public record SpecialBlockRenderContext(BlockPos pos, BlockState state, ClientLevel level,
                                        float partialTick, Vec3 cameraPos, double distanceSqr) {

    /**
     * @return the block centre in world coordinates
     */
    public Vec3 blockCenter() {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * @return distance from the camera to the block centre, in blocks
     */
    public double distance() {
        return Math.sqrt(distanceSqr);
    }
}
