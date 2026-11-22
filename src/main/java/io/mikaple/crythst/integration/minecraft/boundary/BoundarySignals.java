package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Redstone signal evaluation for a block state living in another level.
 *
 * <p>The formulas deliberately mirror {@link SignalGetter}'s default
 * implementations. They are isolated here so the boundary bridge can evaluate a
 * structure voxel without making the auxiliary level the querying level, and so
 * the arithmetic is unit-testable.</p>
 */
public final class BoundarySignals {
    private BoundarySignals() {
    }

    /**
     * Strong signal emitted by {@code state} toward {@code direction}.
     *
     * <p>Matches {@code SignalGetter#getSignal(BlockPos, Direction)}: a
     * conductor also forwards the strongest direct signal from its neighbours.
     * The caller supplies the level that owns {@code pos}; for a structure voxel
     * this is the auxiliary void level.</p>
     */
    public static int signalOf(BlockState state, BlockGetter level, BlockPos pos,
                               Direction direction) {
        if (state == null || level == null || pos == null || direction == null) {
            return 0;
        }
        int signal = state.getSignal(level, pos, direction);
        if (state.isRedstoneConductor(level, pos)) {
            signal = Math.max(signal, directSignalTo(level, pos));
        }
        return signal;
    }

    /** Direct (weak) signal emitted by {@code state} toward {@code direction}. */
    public static int directSignalOf(BlockState state, BlockGetter level, BlockPos pos,
                                     Direction direction) {
        if (state == null || level == null || pos == null || direction == null) {
            return 0;
        }
        return state.getDirectSignal(level, pos, direction);
    }

    /**
     * Maximum direct signal arriving at {@code pos} from its six neighbours.
     * Mirrors {@code SignalGetter#getDirectSignalTo(BlockPos)}.
     */
    public static int directSignalTo(BlockGetter level, BlockPos pos) {
        if (!(level instanceof SignalGetter signalGetter) || pos == null) {
            return 0;
        }
        int best = 0;
        for (Direction direction : Direction.values()) {
            best = Math.max(best, signalGetter.getDirectSignal(
                    pos.relative(direction), direction));
            if (best >= 15) {
                return best;
            }
        }
        return best;
    }
}
