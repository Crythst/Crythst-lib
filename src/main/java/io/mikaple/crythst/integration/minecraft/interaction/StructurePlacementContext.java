package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A {@link BlockPlaceContext} expressed in a structure's local frame.
 *
 * <p>Vanilla placement state hooks read the player's yaw/pitch, the click
 * location and the clicked face. Feeding them the real world click makes
 * stairs, logs, slabs and similar blocks choose world-frame properties while
 * the structure renderer interprets those properties in local space. This
 * context instead exposes the local yaw/pitch, the local click point and the
 * local face, so the resulting state is correct for rotated and scaled
 * structures and for directional-gravity cameras.</p>
 *
 * <p>No fake {@link Player} is allocated and the real player is never mutated.
 * Only the direction/rotation/click accessors are specialised; the real player
 * is still available through {@link #getPlayer()} for placement hooks that ask
 * for gameplay state such as secondary use.</p>
 */
final class StructurePlacementContext extends BlockPlaceContext {
    private final BlockPos localTarget;
    private final Direction localFace;
    private final float localYaw;
    private final float localPitch;
    private final Direction horizontalDirection;
    private final Direction[] nearestLookingDirections;

    StructurePlacementContext(Level level, @Nullable Player player, ItemStack stack,
                              BlockHitResult localClick, BlockPos localTarget,
                              Direction localFace, Vector3dc localLook) {
        super(level, player, InteractionHand.MAIN_HAND, stack, localClick);
        this.localTarget = localTarget;
        this.localFace = localFace == null ? Direction.UP : localFace;
        final Vector3d look = new Vector3d(localLook);
        this.localYaw = StructurePlacementMath.lookYaw(look);
        this.localPitch = StructurePlacementMath.lookPitch(look);
        this.horizontalDirection = StructurePlacementMath.horizontalDirection(localYaw);
        this.nearestLookingDirections =
                reorderForClickedFace(StructurePlacementMath.orderedLookingDirections(look),
                        this.localFace);
        // The target is always the adjacent/replaceable structure cell, never
        // the clicked voxel itself. Validation owns the replacement decision.
        this.replaceClicked = false;
    }

    /**
     * Builds the local context from an authoritative structure hit.
     */
    static StructurePlacementContext create(Player player, ItemStack stack,
                                            StructureBlockHitResult hit) {
        final Transform3d pose = hit.structurePose();
        final double scale = hit.blockScale();
        final Vector3d localPoint = StructurePlacementMath.localPoint(
                pose, hit.worldHit(), scale);
        final Vector3d localLook = StructurePlacementMath.localLook(
                pose, PlayerGravity.lookDirection(player));
        final BlockHitResult localClick = StructurePlacementMath.localBlockHit(
                localPoint, hit.localFace(),
                hit.voxelX(), hit.voxelY(), hit.voxelZ());
        final BlockPos target = new BlockPos(hit.adjacentX(), hit.adjacentY(), hit.adjacentZ());
        return new StructurePlacementContext(player.level(), player, stack, localClick,
                target, hit.localFace(), localLook);
    }

    private static Direction[] reorderForClickedFace(Direction[] base, Direction face) {
        final Direction opposite = face.getOpposite();
        int index = 0;
        while (index < base.length && base[index] != opposite) {
            index++;
        }
        if (index <= 0 || index >= base.length) {
            return base;
        }
        final Direction[] result = base.clone();
        System.arraycopy(result, 0, result, 1, index);
        result[0] = opposite;
        return result;
    }

    @Override
    public BlockPos getClickedPos() {
        return localTarget;
    }

    @Override
    public Direction getHorizontalDirection() {
        return horizontalDirection;
    }

    @Override
    public float getRotation() {
        return localYaw;
    }

    @Override
    public Direction getNearestLookingDirection() {
        return nearestLookingDirections[0];
    }

    @Override
    public Direction getNearestLookingVerticalDirection() {
        return localPitch < 0.0f ? Direction.UP : Direction.DOWN;
    }

    @Override
    public Direction[] getNearestLookingDirections() {
        return nearestLookingDirections.clone();
    }

    @Override
    public boolean replacingClickedOnBlock() {
        return false;
    }

    Direction localFace() {
        return localFace;
    }

    float localYaw() {
        return localYaw;
    }

    float localPitch() {
        return localPitch;
    }
}
