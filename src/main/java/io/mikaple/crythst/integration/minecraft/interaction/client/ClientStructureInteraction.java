package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.interaction.StructureAction;
import io.mikaple.crythst.integration.minecraft.interaction.StructureActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
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
 * <p>It intercepts a left click only when the crosshair really is over a
 * structure (singleplayer snapshots) or over water while holding a shovel.
 * Otherwise it returns {@code false} and vanilla handling proceeds unchanged.</p>
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
    }

    private static boolean onPreAttack(Minecraft client, LocalPlayer player, int clickCount) {
        if (player == null
                || !CrythstFabric.config().structureBlockInteractionEnabled
                || player.isSpectator()) {
            return false;
        }
        final double reach = player.blockInteractionRange();

        final ClientStructureRay.Hit structureHit = ClientStructureRay.raycast(player, reach);
        final boolean holdingShovel = player.getMainHandItem().is(ItemTags.SHOVELS);
        BlockHitResult waterHit = null;
        double waterDistance = Double.POSITIVE_INFINITY;
        if (holdingShovel) {
            final Vec3 eye = player.getEyePosition();
            final Vector3d look = PlayerGravity.lookDirection(player);
            final Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
            final BlockHitResult blockHit = player.level().clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                final FluidState fluid = player.level().getFluidState(blockHit.getBlockPos());
                if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                    waterHit = blockHit;
                    waterDistance = eye.distanceTo(blockHit.getLocation());
                }
            }
        }

        final boolean structureTarget = structureHit != null && structureHit.distance()
                <= waterDistance + StructureInteractionConstants.STRUCTURE_PRIORITY;
        final boolean waterTarget = waterHit != null;
        if (!structureTarget && !waterTarget) {
            return false;
        }
        if (!ClientPlayNetworking.canSend(StructureActionPayload.TYPE)) {
            // If the channel is unavailable, leave vanilla behaviour alone.
            return false;
        }
        // While the attack key is held, keep vanilla block breaking suppressed
        // (otherwise the real block behind a structure would be mined), but only
        // send a structure edit on the initial click.
        if (clickCount == 0) {
            send(player, structureTarget ? StructureAction.MINE : StructureAction.SHOVEL_WATER);
        }
        return true;
    }

    private static boolean send(LocalPlayer player, StructureAction action) {
        ClientPlayNetworking.send(new StructureActionPayload(action));
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        return true;
    }

    /** Local constants shared by the client decision code. */
    private static final class StructureInteractionConstants {
        /** Bias so a structure directly behind water still wins a near-tie. */
        private static final double STRUCTURE_PRIORITY = 0.05;
    }
}
