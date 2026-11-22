package io.mikaple.crythst.integration.minecraft.gameevent;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Small helper for the statistics and advancement triggers that vanilla awards
 * on block break/place/use.
 *
 * <p>The redirected void-level interaction path runs vanilla
 * {@code ServerPlayerGameMode} and already gets these for free. The legacy
 * custom volume-edit fallback does not, so it calls this helper instead of
 * scattering {@code awardStat}/{@code CriteriaTriggers} calls through the
 * interaction code. Positions are always the host-world position of the
 * structure voxel, never the auxiliary region coordinate.</p>
 */
public final class StructurePlayerProgress {
    private StructurePlayerProgress() {
    }

    /** Host block position containing the centre of a structure voxel. */
    public static BlockPos hostBlockPos(PhysicsStructure structure, int x, int y, int z) {
        final Vector3d center = structure.blockCenter(x, y, z);
        return BlockPos.containing(center.x, center.y, center.z);
    }

    /** Awards the vanilla {@code mined} statistic for a removed block. */
    public static void awardBlockMined(ServerPlayer player, @Nullable BlockState state) {
        if (player == null || state == null || state.isAir()) {
            return;
        }
        player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
    }

    /** Awards the vanilla {@code used} statistic for an item. */
    public static void awardItemUsed(ServerPlayer player, @Nullable ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }
        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
    }

    /**
     * Custom-fallback break. Vanilla awards the mined statistic from
     * {@code Block#playerDestroy}; the fallback has no such call.
     */
    public static void onCustomBreak(ServerPlayer player, PhysicsStructure structure,
                                     int x, int y, int z, @Nullable BlockState state) {
        awardBlockMined(player, state);
    }

    /**
     * Custom-fallback placement. Mirrors {@code BlockItem.place} (placed-block
     * trigger) and {@code ItemStack.useOn} (item-used statistic).
     */
    public static void onCustomPlace(ServerPlayer player, PhysicsStructure structure,
                                     int x, int y, int z, @Nullable ItemStack stack) {
        if (player == null || structure == null || stack == null || stack.isEmpty()) {
            return;
        }
        final ItemStack copy = stack.copy();
        player.awardStat(Stats.ITEM_USED.get(copy.getItem()));
        CriteriaTriggers.PLACED_BLOCK.trigger(player, hostBlockPos(structure, x, y, z), copy);
    }

    /** Trigger for a successful {@code BlockState#useItemOn}. */
    public static void triggerItemUsedOnBlock(ServerPlayer player, PhysicsStructure structure,
                                              int x, int y, int z, @Nullable ItemStack stack) {
        if (player == null || structure == null || stack == null) {
            return;
        }
        CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(player,
                hostBlockPos(structure, x, y, z), stack.copy());
    }

    /** Trigger for a successful empty-hand {@code useWithoutItem}. */
    public static void triggerDefaultBlockUse(ServerPlayer player,
                                              PhysicsStructure structure,
                                              int x, int y, int z) {
        if (player == null || structure == null) {
            return;
        }
        CriteriaTriggers.DEFAULT_BLOCK_USE.trigger(player,
                hostBlockPos(structure, x, y, z));
    }

    /**
     * Custom-fallback block use. Chooses the same trigger vanilla's
     * {@code ServerPlayerGameMode#useItemOn} would fire.
     *
     * @param emptyHandUse whether the consuming action came from
     *                     {@code useWithoutItem}
     */
    public static void onCustomUse(ServerPlayer player, PhysicsStructure structure,
                                   int x, int y, int z, @Nullable ItemStack stack,
                                   boolean emptyHandUse) {
        if (emptyHandUse || stack == null || stack.isEmpty()) {
            triggerDefaultBlockUse(player, structure, x, y, z);
        } else {
            triggerItemUsedOnBlock(player, structure, x, y, z, stack);
        }
    }
}
