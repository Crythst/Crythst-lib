package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side registration for structure interaction.
 *
 * <p>Only one line is needed from the common entrypoint:
 * {@code StructureInteractionEvents.registerCommon();}. The client side calls
 * {@code io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureInteraction.register();}.</p>
 *
 * <p>Mining and placement use Fabric's existing
 * {@link AttackBlockCallback}/{@link UseBlockCallback}/{@link UseItemCallback}
 * hooks. The custom payload covers clicks that vanilla never reports to the
 * server: mining a virtual block with air behind it, placing on a virtual-air
 * target, and shovel-water recoil.</p>
 */
public final class StructureInteractionEvents {
    private static final AtomicBoolean COMMON_REGISTERED = new AtomicBoolean();

    private StructureInteractionEvents() {
    }

    /**
     * Registers payloads, the server receiver, callbacks and the diagnostic
     * commands. Safe to call more than once.
     */
    public static void registerCommon() {
        if (!COMMON_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        PayloadTypeRegistry.serverboundPlay().register(StructureActionPayload.TYPE,
                StructureActionPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StructureActionPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> StructureInteraction.handleAction(context.player(), payload.action())));

        AttackBlockCallback.EVENT.register(StructureInteractionEvents::onAttackBlock);
        UseBlockCallback.EVENT.register(StructureInteractionEvents::onUseBlock);
        UseItemCallback.EVENT.register(StructureInteractionEvents::onUseItem);
        CommandRegistrationCallback.EVENT.register(StructureInteractionCommand::register);
    }

    /**
     * Server half of left-clicking a block. If a physics structure is in front
     * of the vanilla hit, the edit is applied and vanilla block breaking is
     * cancelled.
     */
    private static InteractionResult onAttackBlock(Player player, Level level,
                                                   InteractionHand hand, BlockPos pos,
                                                   Direction direction) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || hand != InteractionHand.MAIN_HAND
                || serverPlayer.isSpectator()
                || !MinecraftPhysics.config().structureBlockInteractionEnabled) {
            return InteractionResult.PASS;
        }
        final StructureHit hit = StructureInteraction.raycast(serverPlayer,
                serverPlayer.blockInteractionRange());
        if (hit == null || !StructureInteraction.isStructureInFront(hit,
                Vec3.atCenterOf(pos), serverPlayer)) {
            return InteractionResult.PASS;
        }
        StructureInteraction.breakBlock(serverPlayer, hit);
        // Any non-PASS value cancels the vanilla break on the logical server.
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Server half of right-clicking a block. A held block item is placed into
     * the adjacent structure voxel when the structure is in front of the
     * vanilla hit.
     */
    private static InteractionResult onUseBlock(Player player, Level level,
                                                InteractionHand hand, BlockHitResult hitResult) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || hand != InteractionHand.MAIN_HAND
                || serverPlayer.isSpectator()
                || !MinecraftPhysics.config().structureBlockInteractionEnabled) {
            return InteractionResult.PASS;
        }
        final ItemStack stack = serverPlayer.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return InteractionResult.PASS;
        }
        final StructureHit hit = StructureInteraction.raycast(serverPlayer,
                serverPlayer.blockInteractionRange());
        if (hit == null || !StructureInteraction.isStructureInFront(hit,
                hitResult.getLocation(), serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!StructureInteraction.beginPlacement(serverPlayer)) {
            return InteractionResult.SUCCESS_SERVER;
        }
        final StructureInteraction.PlaceResult result = StructureInteraction.placeBlock(
                serverPlayer, hit, blockItem.getBlock().defaultBlockState());
        if (!result.success()) {
            return InteractionResult.FAIL;
        }
        if (!serverPlayer.isCreative()) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Fallback for right-clicking air with a block item. Vanilla still sends a
     * use-item packet in this case on most clients; without this hook placement
     * would only work when a real block happened to be behind the structure.
     */
    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || hand != InteractionHand.MAIN_HAND
                || serverPlayer.isSpectator()
                || !MinecraftPhysics.config().structureBlockInteractionEnabled) {
            return InteractionResult.PASS;
        }
        final ItemStack stack = serverPlayer.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return InteractionResult.PASS;
        }
        final StructureHit hit = StructureInteraction.raycast(serverPlayer,
                serverPlayer.blockInteractionRange());
        if (hit == null || !StructureInteraction.isStructureInFrontOfRealBlocks(serverPlayer, hit)) {
            return InteractionResult.PASS;
        }
        if (!StructureInteraction.beginPlacement(serverPlayer)) {
            return InteractionResult.SUCCESS_SERVER;
        }
        final StructureInteraction.PlaceResult result = StructureInteraction.placeBlock(
                serverPlayer, hit, blockItem.getBlock().defaultBlockState());
        if (!result.success()) {
            return InteractionResult.FAIL;
        }
        if (!serverPlayer.isCreative()) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
