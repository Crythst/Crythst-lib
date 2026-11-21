package io.mikaple.crythst.api.block;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Opaque snapshot of the block states in a structure volume.
 *
 * <p>Created by {@link StructureBlockHooks#captureBlockStates} and consumed by
 * {@link StructureBlockHooks#fireBlockChanges}. Instances are immutable from the
 * caller's point of view; the internal map is never mutated after construction.
 * The type exists so core physics code can hold a snapshot without depending on
 * Minecraft block-state classes directly.</p>
 */
public final class StructureBlockStateSnapshot {
    private final Map<Long, BlockState> states;

    StructureBlockStateSnapshot(Map<Long, BlockState> states) {
        this.states = Map.copyOf(states);
    }

    Map<Long, BlockState> states() {
        return states;
    }
}
