package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignApplicator;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * Structure-aware replacement for vanilla sign editing.
 *
 * <p>Vanilla opens a sign editor with a real {@link BlockPos} and resolves the
 * {@code SignBlockEntity} from the player's host level on the client. A
 * structure sign lives in the auxiliary void level, so the client cannot see
 * it. This class mirrors the server half of {@code SignBlock#useWithoutItem},
 * sends {@link StructureSignOpenPayload} with an explicit structure identity,
 * and accepts the resulting {@link StructureSignUpdatePayload} after
 * re-validating reach and line of sight.</p>
 *
 * <p>Honeycomb/glow-ink applicators are applied in the same structure-local
 * pass, so they also honour the face the player is looking at and the normal
 * mirror pass persists the result.</p>
 */
public final class StructureSignInteraction {
    /**
     * Vanilla's sign editor allows editing within four blocks even when the
     * player's block interaction range is smaller.
     */
    private static final double MIN_EDIT_RANGE = 4.0;

    private StructureSignInteraction() {
    }

    /**
     * Runs the sign use/editor path when {@code live} is an editable sign.
     *
     * @param voxelX structure-local voxel X from the authoritative hit
     * @param voxelY structure-local voxel Y
     * @param voxelZ structure-local voxel Z
     * @return the interaction result, or {@code null} when the target is not a
     *         sign or an applicator should be handled by vanilla
     */
    public static @Nullable InteractionResult tryUse(
            ServerPlayer player, PhysicsStructure structure,
            EmptyPhysicsLevel.LiveBlock live, BlockHitResult auxiliaryHit,
            ItemStack stack, InteractionHand hand,
            int voxelX, int voxelY, int voxelZ) {
        if (player == null || structure == null || live == null || stack == null) {
            return null;
        }
        if (!(live.blockEntity() instanceof SignBlockEntity sign)
                || !(live.state().getBlock() instanceof SignBlock)) {
            return null;
        }
        final ServerLevel level = live.level();
        final BlockPos pos = live.pos();
        final SignBlock signBlock = (SignBlock) live.state().getBlock();
        // The vanilla helper compares host-world player coordinates with the
        // auxiliary block position, which selects the wrong face for a moved
        // or rotated structure. Compute the same test in structure-local
        // space instead.
        final SignTextSlot slot = facingSlot(player, structure, live.state(),
                signBlock, voxelX, voxelY, voxelZ);

        // Mirror SignBlock#useItemOn for honeycomb/glow ink, with the
        // structure-local slot. If the applicator cannot be used, vanilla
        // falls through to the empty-hand path below.
        if (stack.getItem() instanceof SignApplicator applicator
                && !sign.isWaxed()
                && !isOtherPlayerEditing(player, sign)
                && player.mayBuild()) {
            if (applicator.canApplyToSign(sign.getText(slot), stack, player)
                    && applicator.tryApplyToSign(level, sign, slot, stack, player)) {
                sign.executeClickCommandsIfPresent(level, player, pos, slot);
                player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
                level.gameEvent(GameEvent.BLOCK_CHANGE, pos,
                        GameEvent.Context.of(player, live.state()));
                stack.consume(1, player);
                return InteractionResult.SUCCESS;
            }
        }

        // Mirrors SignBlock#useWithoutItem on the server branch.
        final boolean executedClickCommand =
                sign.executeClickCommandsIfPresent(level, player, pos, slot);
        if (sign.isWaxed()) {
            level.playSound(null, pos, sign.getSignInteractionFailedSoundEvent(),
                    SoundSource.BLOCKS);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (executedClickCommand) {
            return InteractionResult.SUCCESS_SERVER;
        }
        if (isOtherPlayerEditing(player, sign)
                || !player.mayBuild()
                || !sign.getText(slot).hasEditableText(player.isTextFilteringEnabled())) {
            return InteractionResult.PASS;
        }
        if (!ServerPlayNetworking.canSend(player, StructureSignOpenPayload.TYPE)) {
            // The client cannot display the structure editor. Consume the
            // click rather than letting the vanilla packet open a real sign at
            // the auxiliary coordinates.
            return InteractionResult.SUCCESS_SERVER;
        }

        sign.setAllowedPlayerEditor(player.getUUID());
        final Vector3d worldCenter = StructureParticleMath.voxelCenter(
                structure.pose(), voxelX, voxelY, voxelZ, structure.blockScale());
        final BlockPos worldPos = BlockPos.containing(
                worldCenter.x, worldCenter.y, worldCenter.z);
        final CompoundTag data = sign.saveWithFullMetadata(level.registryAccess());
        ServerPlayNetworking.send(player, new StructureSignOpenPayload(
                structure.body().id(), voxelX, voxelY, voxelZ, slot,
                Block.getId(live.state()), worldPos, data));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Handles a {@link StructureSignUpdatePayload} on the server thread.
     */
    public static void handleUpdate(ServerPlayer player, StructureSignUpdatePayload payload) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (config == null || !config.structureBlockInteractionEnabled
                || player == null || payload == null
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics == null) {
            return;
        }
        final PhysicsStructure structure = findStructure(physics, payload.bodyId());
        if (structure == null || structure.closed()) {
            return;
        }
        final int x = payload.voxelX();
        final int y = payload.voxelY();
        final int z = payload.voxelZ();
        // Signs have no collision shape, so volume.isSolid is false for a
        // normal sign cell. The live auxiliary block entity is the authority.
        if (!structure.volume().bounds().contains(x, y, z)) {
            return;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        final EmptyPhysicsLevel.LiveBlock live = auxiliary.liveBlock(structure, x, y, z);
        if (live == null || !(live.blockEntity() instanceof SignBlockEntity sign)
                || !(live.state().getBlock() instanceof SignBlock)) {
            return;
        }
        // The editor must have been opened by this player. This is the same
        // authorization rule vanilla's updateSignText enforces.
        final UUID allowed = sign.getPlayerWhoMayEdit();
        if (allowed == null || !allowed.equals(player.getUUID())) {
            return;
        }
        if (!withinEditRange(player, structure, x, y, z)
                || !hasLineOfSight(player, structure, x, y, z)) {
            sign.setAllowedPlayerEditor(null);
            return;
        }

        final List<String> lines = payload.lines().stream()
                .map(ChatFormatting::stripFormatting)
                .toList();
        player.getTextFilter().processMessageBundle(lines).thenAcceptAsync(
                filtered -> applyTextUpdate(player, structure, auxiliary, sign,
                        payload.slot(), filtered),
                level.getServer());
    }

    private static void applyTextUpdate(ServerPlayer player, PhysicsStructure structure,
                                        EmptyPhysicsLevel auxiliary, SignBlockEntity sign,
                                        SignTextSlot slot, List<FilteredText> lines) {
        if (player.hasDisconnected() || player.isRemoved() || structure.closed()
                || sign.isRemoved()) {
            return;
        }
        final UUID allowed = sign.getPlayerWhoMayEdit();
        if (allowed == null || !allowed.equals(player.getUUID())) {
            return;
        }
        sign.updateSignText(player, slot, lines);
        // Persist the live auxiliary NBT and publish it to local renderers.
        auxiliary.mirrorBlockEntities();
        structure.bumpBlockEntityRevision();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        if (physics != null) {
            physics.captureSnapshotNow(structure);
        }
    }

    /**
     * Structure-local equivalent of {@link SignBlockEntity#getSlotPlayerIsFacing}.
     * The player position is transformed into the structure frame before the
     * front/back comparison, so a sign on a rotated body still edits the face
     * the player is actually looking at.
     */
    private static SignTextSlot facingSlot(ServerPlayer player, PhysicsStructure structure,
                                           net.minecraft.world.level.block.state.BlockState state,
                                           SignBlock signBlock,
                                           int voxelX, int voxelY, int voxelZ) {
        final double scale = StructureParticleMath.safeScale(structure.blockScale());
        final Vector3d localPlayer = structure.pose().inverseTransformPoint(new Vector3d(
                player.getX(), player.getY(), player.getZ())).mul(1.0 / scale);
        final Vec3 offset = signBlock.getSignHitboxCenterPosition(state);
        final double xd = localPlayer.x - (voxelX + offset.x);
        final double zd = localPlayer.z - (voxelZ + offset.z);
        final float signYRot = signBlock.getYRotationDegrees(state);
        final float playerYRot = (float) (net.minecraft.util.Mth.atan2(zd, xd)
                * 180.0F / (float) Math.PI) - 90.0F;
        return net.minecraft.util.Mth.degreesDifferenceAbs(signYRot, playerYRot) <= 90.0F
                ? SignTextSlot.FRONT : SignTextSlot.BACK;
    }

    private static boolean isOtherPlayerEditing(ServerPlayer player, SignBlockEntity sign) {
        final UUID editor = sign.getPlayerWhoMayEdit();
        return editor != null && !editor.equals(player.getUUID());
    }

    private static boolean withinEditRange(ServerPlayer player, PhysicsStructure structure,
                                           int x, int y, int z) {
        final Vector3d center = StructureParticleMath.voxelCenter(
                structure.pose(), x, y, z, structure.blockScale());
        final double range = Math.max(MIN_EDIT_RANGE, player.blockInteractionRange())
                + StructureParticleMath.safeScale(structure.blockScale()) * 0.5;
        return player.getEyePosition().distanceToSqr(center.x, center.y, center.z) <= range * range;
    }

    /**
     * Clips from the player's eye toward the sign centre. The check is
     * independent of the current look direction so the editor does not close
     * merely because the player moves the mouse while typing.
     */
    private static boolean hasLineOfSight(ServerPlayer player, PhysicsStructure structure,
                                          int x, int y, int z) {
        final Vector3d center = StructureParticleMath.voxelCenter(
                structure.pose(), x, y, z, structure.blockScale());
        final Vec3 eye = player.getEyePosition();
        final Vec3 target = new Vec3(center.x, center.y, center.z);
        final double distance = eye.distanceTo(target);
        final BlockHitResult realHit = player.level().clip(new ClipContext(
                eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (realHit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return distance + StructureInteraction.FRONT_SLACK <= eye.distanceTo(realHit.getLocation());
    }

    private static PhysicsStructure findStructure(LevelPhysics physics, long bodyId) {
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.body().id() == bodyId) {
                return structure;
            }
        }
        return null;
    }
}
