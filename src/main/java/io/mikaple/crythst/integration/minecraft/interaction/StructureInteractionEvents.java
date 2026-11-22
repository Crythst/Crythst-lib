package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.collision.StructureCollisionSources;
import io.mikaple.crythst.integration.minecraft.entity.placement.StructureEntityPlacement;
import io.mikaple.crythst.integration.minecraft.entity.placement.StructureEntityPlacementHandler;
import io.mikaple.crythst.integration.minecraft.operation.StructureOperationRedirector;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
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

        StructureMiningSessions.register();
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
        final StructureBlockHitResult hit = StructureInteraction.raycast(serverPlayer,
                serverPlayer.blockInteractionRange());
        if (hit == null || !StructureInteraction.isStructureInFrontOfRealBlocks(serverPlayer, hit)) {
            return InteractionResult.PASS;
        }
        if (StructureOperationRedirector.enabled()
                && StructureMiningSessions.start(serverPlayer, hit)) {
            // Vanilla game-mode progress now owns this held attack.
            return InteractionResult.SUCCESS_SERVER;
        }
        // Auxiliary region/block unavailable: preserve the redirector's
        // game-mode restrictions and use the custom edit only when it declines.
        StructureInteraction.mineOnce(serverPlayer);
        // Any non-PASS value cancels the vanilla break on the logical server.
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Server half of right-clicking a block. Handled placement items (block
     * items, spawn eggs, item frames and registered entity items) place into the
     * structure as before; every other stack runs the live vanilla block-use
     * path in {@link StructureInteraction#interactBlock}. Always resolves one
     * authoritative structure hit first, so a closer real block keeps vanilla.
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
        final StructureBlockHitResult hit = StructureInteraction.raycast(serverPlayer,
                serverPlayer.blockInteractionRange());
        if (hit == null || !StructureInteraction.isStructureInFrontOfRealBlocks(serverPlayer, hit)) {
            // Placing a real-world block must not ignore a structure occupying
            // the target cell (structures are air in the vanilla block grid).
            if (stack.getItem() instanceof BlockItem
                    && StructurePlacementOcclusion.obstructed(
                            StructureCollisionSources.forEntity(serverPlayer),
                            level, hitResult)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }
        return useStructure(serverPlayer, hit, stack);
    }

    /**
     * Fallback for right-clicking air. Vanilla still sends a use-item packet
     * in this case on most clients; without this hook placement would only
     * work when a real block happened to be behind the structure. Placement
     * items and ordinary block use share the same authoritative structure hit.
     */
    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || hand != InteractionHand.MAIN_HAND
                || serverPlayer.isSpectator()
                || !MinecraftPhysics.config().structureBlockInteractionEnabled) {
            return InteractionResult.PASS;
        }
        final ItemStack stack = serverPlayer.getMainHandItem();
        final StructureBlockHitResult hit = StructureInteraction.raycast(serverPlayer,
                serverPlayer.blockInteractionRange());
        if (hit == null || !StructureInteraction.isStructureInFrontOfRealBlocks(serverPlayer, hit)) {
            return InteractionResult.PASS;
        }
        return useStructure(serverPlayer, hit, stack);
    }

    /**
     * Shared right-click routing for the Fabric callbacks.
     *
     * <p>Block items and ordinary use items first run vanilla
     * {@code ServerPlayerGameMode.useItemOn} against the auxiliary void level.
     * A handled/failed redirect consumes the click; an out-of-bounds block
     * placement or an unavailable auxiliary region/chunk falls back to the
     * legacy custom path. Entity items keep the structure-surface handler
     * because vanilla would spawn them in the copied block cell.</p>
     */
    private static InteractionResult useStructure(
            net.minecraft.server.level.ServerPlayer player,
            StructureBlockHitResult hit, ItemStack stack) {
        final boolean placementItem = StructureItemUse.shouldHandleStructureUse(stack);
        final boolean redirectable = StructureOperationRedirector.enabled()
                && (!placementItem || stack.getItem() instanceof BlockItem);
        if (redirectable) {
            if (!StructureInteraction.beginInteraction(player)) {
                // The payload or the other use callback already ran this click.
                return InteractionResult.SUCCESS_SERVER;
            }
            final StructureOperationRedirector.Result result =
                    StructureOperationRedirector.useOn(player, hit, InteractionHand.MAIN_HAND);
            if (result.handled()
                    || result.status() == StructureOperationRedirector.Status.FAILED) {
                return InteractionResult.SUCCESS_SERVER;
            }
            if (!StructureOperationRedirector.shouldFallback(result.status())) {
                return InteractionResult.SUCCESS_SERVER;
            }
            // OUT_OF_BOUNDS placement and NOT_HANDLED (unloaded aux chunk)
            // continue to the legacy custom path below.
        }

        if (!placementItem) {
            // Empty hand, tools, buckets, food, ...: fallback vanilla block-use
            // against the structure side table/live block.
            return StructureInteraction.interactBlock(player, hit).consumesAction()
                    ? InteractionResult.SUCCESS_SERVER
                    : InteractionResult.PASS;
        }
        if (!StructureInteraction.beginPlacement(player)) {
            return InteractionResult.SUCCESS_SERVER;
        }
        if (stack.getItem() instanceof BlockItem) {
            final StructureInteraction.PlaceResult result = StructureInteraction.placeBlock(
                    player, hit, stack);
            if (!result.success()) {
                return InteractionResult.FAIL;
            }
            stack.consume(1, player);
            return InteractionResult.SUCCESS_SERVER;
        }
        final StructureEntityPlacementHandler.Result result = StructureEntityPlacement.place(
                player, stack, hit, InteractionHand.MAIN_HAND);
        if (!result.success()) {
            return InteractionResult.FAIL;
        }
        stack.consume(1, player);
        return InteractionResult.SUCCESS_SERVER;
    }
}
