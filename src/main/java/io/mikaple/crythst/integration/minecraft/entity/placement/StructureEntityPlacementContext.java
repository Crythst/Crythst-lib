package io.mikaple.crythst.integration.minecraft.entity.placement;

import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable context handed to a {@link StructureEntityPlacementHandler}.
 *
 * @param player  the server player performing the placement
 * @param level   the server level
 * @param stack   the held stack; handlers must not consume it
 * @param hit     authoritative structure hit (server re-raycast)
 * @param surface derived world-space surface anchors
 * @param hand    hand used for the placement
 */
public record StructureEntityPlacementContext(
        ServerPlayer player,
        ServerLevel level,
        ItemStack stack,
        StructureBlockHitResult hit,
        StructureEntityPlacementMath.SurfacePoint surface,
        InteractionHand hand
) {
    /** The actual ray hit moved just outside the structure face. */
    public Vec3 hitPoint() {
        return surface.hitPoint();
    }

    /** Face-centred spawn point; recommended for block-like entity items. */
    public Vec3 spawnPoint() {
        return surface.spawnPoint();
    }

    /** Outward cardinal world face. */
    public Direction face() {
        return surface.face();
    }

    /** Real block cell just outside the clicked face, for vanilla spawn logic. */
    public BlockPos spawnBlockPos() {
        return surface.spawnBlockPos();
    }
}
