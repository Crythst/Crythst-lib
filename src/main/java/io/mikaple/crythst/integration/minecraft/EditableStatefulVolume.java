package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A {@link StatefulVoxelVolume} that also accepts edits.
 *
 * <p>Split components wrap their voxel grid in a stateful volume so the
 * auxiliary ticking level and the block-state snapshot keep the real block
 * states, but that wrapper is not a {@code MutableVoxelVolume}. Interaction,
 * explosions and the ticking level must not probe for the concrete mutable
 * class: one shared "stateful and editable" contract keeps split bodies
 * editable instead of silently rejecting every edit.</p>
 *
 * <p>Implementations must keep the collision grid and the block states in
 * sync: clearing a voxel also clears its state, so a later read cannot
 * resurrect a removed block.</p>
 */
public interface EditableStatefulVolume extends StatefulVoxelVolume {
    /**
     * Writes one voxel and its state atomically.
     *
     * @param solid    whether the voxel participates in collision
     * @param mass     voxel mass in kilograms, ignored when {@code solid} is false
     * @param material collision material, ignored when {@code solid} is false
     * @param state    the block state, or {@code null} to clear it
     */
    void setBlock(int x, int y, int z, boolean solid, double mass,
                  PhysicsMaterial material, BlockState state);
}
