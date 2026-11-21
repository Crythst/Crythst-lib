package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.interaction.StructureAction;
import io.mikaple.crythst.integration.minecraft.interaction.StructureActionPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
 * Optional client hook for clicks that vanilla never turns into a server packet.
 *
 * <p>Left clicks are intercepted by {@link ClientPreAttackCallback}. Right
 * clicks with a block item over a virtual (air) target are handled by a client
 * tick poll because Fabric has no equivalent pre-use callback; a real block
 * target is left to vanilla and the server-side {@code UseBlockCallback}.</p>
 *
 * <p>All decisions compare the structure ray with a real-block clip using
 * {@link PlayerGravity#lookDirection} so gravity-rotated cameras behave
 * correctly.</p>
 */
public final class ClientStructureInteraction {
    /** Structure must be at least this much closer than a real block to win. */
    private static final double OCCLUSION_SLACK = 0.05;
    /** Bias so a structure directly behind water still wins a near-tie. */
    private static final double STRUCTURE_PRIORITY = 0.05;
    /** Repeat interval for hold-to-mine; one voxel per interval. */
    private static final int MINE_REPEAT_TICKS = 5;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static boolean lastUseDown;
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
        ClientTickEvents.END_CLIENT_TICK.register(ClientStructureInteraction::tickPlacement);
    }

    private static boolean onPreAttack(Minecraft client, LocalPlayer player, int clickCount) {
        if (player == null
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || player.isSpectator()) {
            return false;
        }
        final double reach = player.blockInteractionRange();
        final Vec3 eye = player.getEyePosition();
        final ClientStructureRay.Hit structureHit = ClientStructureRay.raycast(player, reach);
        final BlockHitResult realBlock = blockHit(player, reach, ClipContext.Fluid.NONE);
        final double realDistance = distance(eye, realBlock);
        final boolean holdingShovel = player.getMainHandItem().is(ItemTags.SHOVELS);

        BlockHitResult waterHit = null;
        double waterDistance = Double.POSITIVE_INFINITY;
        if (holdingShovel) {
            final BlockHitResult fluidHit = blockHit(player, reach, ClipContext.Fluid.ANY);
            if (fluidHit.getType() == HitResult.Type.BLOCK) {
                final FluidState fluid = player.level().getFluidState(fluidHit.getBlockPos());
                if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                    waterHit = fluidHit;
                    waterDistance = eye.distanceTo(fluidHit.getLocation());
                }
            }
        }

        final boolean structureTarget = structureHit != null
                && structureHit.distance() <= realDistance + OCCLUSION_SLACK
                && structureHit.distance() <= waterDistance + STRUCTURE_PRIORITY;
        final boolean waterTarget = waterHit != null;
        if (!structureTarget && !waterTarget) {
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
        if (structureTarget) {
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

    private static void tickPlacement(Minecraft client) {
        final boolean down = client.options.keyUse.isDown();
        final boolean pressed = down && !lastUseDown;
        lastUseDown = down;
        if (!pressed || client.player == null || client.gui.screen() != null) {
            return;
        }
        final LocalPlayer player = client.player;
        if (!CrythstFabric.config().structureBlockInteractionEnabled
                || player.isSpectator()
                || !(player.getMainHandItem().getItem() instanceof BlockItem)) {
            return;
        }
        if (client.crosshairPickEntity != null) {
            // Let vanilla handle an entity interaction in front of the structure.
            return;
        }
        final double reach = player.blockInteractionRange();
        final ClientStructureRay.Hit structureHit = ClientStructureRay.raycast(player, reach);
        if (structureHit == null) {
            return;
        }
        final BlockHitResult realBlock = blockHit(player, reach, ClipContext.Fluid.NONE);
        if (realBlock.getType() != HitResult.Type.MISS) {
            // A real block is in reach. Vanilla will send useOn and the server
            // UseBlockCallback decides whether the structure is in front.
            return;
        }
        if (!ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            return;
        }
        send(player, StructureAction.PLACE);
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

    private static double distance(Vec3 eye, HitResult hit) {
        return hit == null || hit.getType() != HitResult.Type.BLOCK
                ? Double.POSITIVE_INFINITY
                : eye.distanceTo(hit.getLocation());
    }
}
