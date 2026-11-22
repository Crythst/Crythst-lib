package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.client.render.structure.breakoverlay.StructureBreakOverlay;
import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.interaction.StructureAction;
import io.mikaple.crythst.integration.minecraft.interaction.StructureActionPayload;
import io.mikaple.crythst.integration.minecraft.interaction.StructureItemUse;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional client hook for clicks that vanilla never turns into a server
 * packet.
 *
 * <p>Left clicks are intercepted by {@link ClientPreAttackCallback}. Right
 * clicks are intercepted by {@code StructurePlacementMixin} at the head of
 * {@code MultiPlayerGameMode.useItemOn}, before 26.3's client prediction can
 * run {@code BlockItem.place}; see {@link #requestStructureUse}. A real
 * block target is left to vanilla and the server-side
 * {@code UseBlockCallback}.</p>
 *
 * <p>All decisions compare the structure ray with a real-block clip using
 * {@link PlayerGravity#lookDirection} so gravity-rotated cameras behave
 * correctly.</p>
 */
public final class ClientStructureInteraction {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ClientStructureInteraction() {
    }

    /**
     * Called from the client entrypoint.
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ClientPreAttackCallback.EVENT.register(ClientStructureInteraction::onPreAttack);
        StructureMiningController.register();
        // Registered after the controller so a key release in the controller's
        // END_CLIENT_TICK pass can be turned into one STOP packet here.
        ClientTickEvents.END_CLIENT_TICK.register(ClientStructureInteraction::flushMiningStop);
        StructureBreakOverlay.register();
        // Register the vanilla-outline override here because the client
        // entrypoint already calls this method. It must run after vanilla has
        // extracted its own block outline.
        io.mikaple.crythst.client.render.structure.selection.StructureSelectionOutline.register();
        StructureSignClient.register();
    }

    private static boolean onPreAttack(Minecraft client, LocalPlayer player, int clickCount) {
        if (player == null
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || player.isSpectator()) {
            stopMining(player);
            return false;
        }
        final double reach = player.blockInteractionRange();
        // This is exactly the hit Minecraft shows and uses for interactions.
        // ClientLevel.clip returns it, so a real block or entity that is closer
        // has already replaced it and the crosshair target is never a second,
        // differently-quantised ray.
        final StructureBlockHitResult structureHit = ClientStructureSelection.current(client);
        final boolean holdingShovel = player.getMainHandItem().is(ItemTags.SHOVELS);

        BlockHitResult waterHit = null;
        if (structureHit == null && holdingShovel) {
            final BlockHitResult fluidHit = blockHit(player, reach, ClipContext.Fluid.ANY);
            if (fluidHit.getType() == HitResult.Type.BLOCK) {
                final FluidState fluid = player.level().getFluidState(fluidHit.getBlockPos());
                if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                    waterHit = fluidHit;
                }
            }
        }

        final StructureMiningController.AttackRoute route =
                StructureMiningController.route(structureHit != null, waterHit != null);
        if (route == StructureMiningController.AttackRoute.PASS) {
            stopMining(player);
            return false;
        }
        if (!ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            // If the channel is unavailable, leave vanilla behaviour alone.
            stopMining(player);
            return false;
        }

        if (route == StructureMiningController.AttackRoute.MINE) {
            // The server owns progress and completion. START creates the
            // authoritative void-level session, TICK is a heartbeat and STOP
            // aborts it; no progress value crosses the wire.
            final StructureMiningController.Update update =
                    StructureMiningController.tick(client, player, structureHit, clickCount);
            switch (update) {
                case START -> {
                    // Drop any previous session before starting a new target.
                    StructureMiningController.consumeStopRequest();
                    send(player, StructureAction.STOP_MINE, false);
                    send(player, StructureAction.START_MINE);
                }
                case TICK -> send(player, StructureAction.TICK_MINE, false);
                case STOP -> {
                    StructureMiningController.consumeStopRequest();
                    send(player, StructureAction.STOP_MINE, false);
                }
                case NONE -> {
                    // Progress is rendered by StructureBreakOverlay.
                }
            }
            // Suppress vanilla block breaking while the crosshair is on a
            // structure, otherwise the real block behind it would be mined.
            return true;
        }

        // Shovel + water is still a one-shot action, not a held break.
        stopMining(player);
        if (clickCount != 0) {
            send(player, StructureAction.SHOVEL_WATER);
        }
        return true;
    }

    /**
     * END_CLIENT_TICK flush for a STOP requested by the controller's release
     * or target-loss reset.
     */
    private static void flushMiningStop(Minecraft client) {
        if (!StructureMiningController.consumeStopRequest()) {
            return;
        }
        final LocalPlayer player = client.player;
        if (player != null && ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            ClientPlayNetworking.send(new StructureActionPayload(StructureAction.STOP_MINE));
        }
    }

    /**
     * Stops a held session immediately and sends one STOP if it was active.
     */
    private static void stopMining(LocalPlayer player) {
        if (StructureMiningController.stop() == StructureMiningController.Update.STOP) {
            StructureMiningController.consumeStopRequest();
            if (player != null && ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
                ClientPlayNetworking.send(new StructureActionPayload(StructureAction.STOP_MINE));
            }
        }
    }

    /**
     * Intercepts a vanilla use-on-block before 26.3's client prediction runs.
     *
     * <p>{@code MultiPlayerGameMode.useItemOn} evaluates a predictive action
     * (including {@code BlockItem.place}) on the client before it sends
     * {@code ServerboundUseItemOnPacket}. For a structure hit that would paint
     * a real block for one tick and play a local place sound, and the server
     * would later remove the predicted block, producing the ghost-block and
     * double-sound bugs. The mixin calls this method at the head of
     * {@code useItemOn}; returning {@code true} replaces the whole prediction
     * with a single structure action payload.</p>
     *
     * <p>Only the main hand is handled: the placement payload is
     * server-authoritative and always resolves the main-hand stack. Other
     * hands keep vanilla behaviour.</p>
     *
     * <p>Block items, spawn eggs, item frames and items with a registered
     * {@link io.mikaple.crythst.integration.minecraft.entity.placement.StructureEntityPlacementHandler}
     * are consumed as {@link StructureAction#PLACE}. Everything else - an empty
     * hand, tools, buckets, food and other items - is routed to
     * {@link StructureAction#USE}, which runs vanilla block interaction on the
     * server. In both cases the server re-raycasts; no client position is
     * sent.</p>
     *
     * @return whether the click was consumed as a structure action
     */
    public static boolean requestStructureUse(LocalPlayer player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND
                || player == null
                || !(hitResult instanceof StructureBlockHitResult)
                || player.isSpectator()
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || !player.level().getWorldBorder().isWithinBounds(hitResult.getBlockPos())
                || !ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            return false;
        }
        // Do not send ServerboundUseItemOnPacket. Only the authoritative
        // structure action is sent; the server re-raycasts from the player's
        // look vector and never runs BlockItem.place for this click.
        stopMining(player);
        return send(player, StructureItemUse.actionForUse(player.getMainHandItem()));
    }

    /**
     * Replaces vanilla middle-click pick-block for a structure hit.
     *
     * <p>Vanilla would send {@code ServerboundPickItemFromBlockPacket} with the
     * real-world air position; the server would then resolve air and pick
     * nothing. The mixin calls this at the head of
     * {@code Minecraft.pickBlockOrEntity}, before that packet is sent. The
     * server resolves the structure state from its own authoritative raycast
     * and runs vanilla's creative/survival pick logic.</p>
     *
     * @return whether the click was consumed as a structure pick
     */
    public static boolean requestPickBlock(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        final LocalPlayer player = client.player;
        if (!(client.hitResult instanceof StructureBlockHitResult)
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || !ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            return false;
        }
        final StructureAction action = client.hasControlDown()
                ? StructureAction.PICK_WITH_DATA
                : StructureAction.PICK;
        // Pick-block does not swing the arm.
        stopMining(player);
        return send(player, action, false);
    }

    private static boolean send(LocalPlayer player, StructureAction action) {
        return send(player, action, true);
    }

    private static boolean send(LocalPlayer player, StructureAction action, boolean swing) {
        ClientPlayNetworking.send(new StructureActionPayload(action));
        // Do not swing locally: the server may reject the action (occlusion,
        // reach, invalid placement, cooldown). StructureInteraction.handleAction
        // swings only after authoritative success.
        return true;
    }

    private static BlockHitResult blockHit(LocalPlayer player, double reach,
                                           ClipContext.Fluid fluidMode) {
        final Vec3 eye = player.getEyePosition();
        final Vector3d look = PlayerGravity.lookDirection(player);
        final Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        return player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, fluidMode, player));
    }
}
