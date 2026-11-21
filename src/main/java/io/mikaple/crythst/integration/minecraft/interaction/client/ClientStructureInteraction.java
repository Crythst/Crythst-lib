package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.interaction.StructureAction;
import io.mikaple.crythst.integration.minecraft.interaction.StructureActionPayload;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.component.SwingAnimation;
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
 * run {@code BlockItem.place}; see {@link #requestStructurePlacement}. A real
 * block target is left to vanilla and the server-side
 * {@code UseBlockCallback}.</p>
 *
 * <p>All decisions compare the structure ray with a real-block clip using
 * {@link PlayerGravity#lookDirection} so gravity-rotated cameras behave
 * correctly.</p>
 */
public final class ClientStructureInteraction {
    /** Repeat interval for hold-to-mine; one voxel per interval. */
    private static final int MINE_REPEAT_TICKS = 5;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static int lastMineTick = Integer.MIN_VALUE / 2;

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
        // Register the vanilla-outline override here because the client
        // entrypoint already calls this method. It must run after vanilla has
        // extracted its own block outline.
        io.mikaple.crythst.client.render.structure.selection.StructureSelectionOutline.register();
    }

    private static boolean onPreAttack(Minecraft client, LocalPlayer player, int clickCount) {
        if (player == null
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || player.isSpectator()) {
            return false;
        }
        final double reach = player.blockInteractionRange();
        final Vec3 eye = player.getEyePosition();
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

        if (structureHit == null && waterHit == null) {
            return false;
        }
        if (!ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            // If the channel is unavailable, leave vanilla behaviour alone.
            return false;
        }
        // Fabric reads KeyMapping.clickCount before vanilla consumes it, so a
        // fresh press has clickCount >= 1 and a held key has 0. The initial
        // click must use != 0; while held, a slow repeat keeps hold-to-mine
        // working without spending == 0 as the click signal.
        if (structureHit != null) {
            final boolean initialClick = clickCount != 0;
            final boolean repeat = player.tickCount - lastMineTick >= MINE_REPEAT_TICKS;
            if (initialClick || repeat) {
                send(player, StructureAction.MINE);
                lastMineTick = player.tickCount;
            }
        } else if (clickCount != 0) {
            send(player, StructureAction.SHOVEL_WATER);
        }
        // Suppress vanilla block breaking while the crosshair is on a
        // structure, otherwise the real block behind it would be mined.
        return true;
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
     * @return whether the click was consumed as a structure placement
     */
    public static boolean requestStructurePlacement(LocalPlayer player, InteractionHand hand,
                                                    BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND
                || player == null
                || !(hitResult instanceof StructureBlockHitResult)
                || player.isSpectator()
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || !(player.getMainHandItem().getItem() instanceof BlockItem)
                || !player.level().getWorldBorder().isWithinBounds(hitResult.getBlockPos())
                || !ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            return false;
        }
        // Do not send ServerboundUseItemOnPacket. Only the authoritative
        // structure action is sent; the server re-raycasts from the player's
        // look vector and never runs BlockItem.place for this click.
        return send(player, StructureAction.PLACE);
    }

    private static boolean send(LocalPlayer player, StructureAction action) {
        ClientPlayNetworking.send(new StructureActionPayload(action));
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
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
