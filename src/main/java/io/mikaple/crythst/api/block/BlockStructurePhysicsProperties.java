package io.mikaple.crythst.api.block;

import io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Optional per-block mass/surface override layered on top of the data-pack
 * driven {@code BlockPhysicsRegistry}.
 *
 * <p>This interface does not replace {@code data/crythst/block_physics.json}.
 * It is consulted after the data pack and the mod configuration, so a gameplay
 * block can compute a state-dependent mass or friction without editing the data
 * pack. Returning {@code null} keeps the data-pack result.</p>
 *
 * <p>Precedence for structure mass/material: an explicit registry entry
 * overrides this interface; this interface overrides the data pack. The
 * override is applied while a structure's collider is rebuilt, so call
 * {@code PhysicsStructure#reindex} (or the interaction API) after changing the
 * inputs used by the hook.</p>
 */
@FunctionalInterface
public interface BlockStructurePhysicsProperties {

    /**
     * @param level the level containing the block, or {@code null} for a
     *              restored/snapshot-backed volume
     * @param pos   block position or structure-local voxel position
     * @param state block state being queried
     * @return a complete profile to use, or {@code null} to keep the data-pack
     *         and configuration result
     */
    @Nullable BlockPhysicsProfile structurePhysics(@Nullable BlockGetter level,
                                                   BlockPos pos, BlockState state);
}
