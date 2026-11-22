package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.collision.ClientStructureCollisionSource;
import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementOcclusion;
import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureInteraction;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents 26.3's client-side prediction from placing a real block when the
 * player right-clicks a physics structure voxel.
 *
 * <p>{@link MultiPlayerGameMode#useItemOn} runs its predictive action before
 * sending {@code ServerboundUseItemOnPacket}. That action calls
 * {@code BlockItem.place} on the {@code ClientLevel}, which paints a real block
 * for one tick and plays a local place sound. The server then correctly refuses
 * the vanilla placement via Fabric's {@code UseBlockCallback}, so the predicted
 * block disappears, but the ghost and the duplicate sound remain.</p>
 *
 * <p>For a {@code StructureBlockHitResult} this injector consumes the click at
 * the head of {@code useItemOn}, before prediction starts, and sends only the
 * authoritative {@code crythst:structure_action} payload. Block items, spawn
 * eggs, item frames and registered entity items use {@code PLACE}; an empty
 * hand, tools, buckets, food and other items use {@code USE}, which runs the
 * live vanilla block-use path on the server. Vanilla never sends a use-on-block
 * packet and never runs {@code BlockItem.place}, {@code SpawnEggItem.useOn} or
 * a hanging-entity placement for this click. {@code CONSUME} suppresses the
 * predicted swing so the server can animate a successful interaction.</p>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class StructurePlacementMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void crythst$consumeStructurePlacement(LocalPlayer player, InteractionHand hand,
                                                   BlockHitResult hitResult,
                                                   CallbackInfoReturnable<InteractionResult> callback) {
        if (ClientStructureInteraction.requestStructureUse(player, hand, hitResult)) {
            callback.setReturnValue(InteractionResult.CONSUME);
            return;
        }
        // A real-world block target can still be occupied by a structure. Stop
        // the predictive BlockItem.place rather than sending a packet the
        // server will reject (which would leave a ghost block for one tick).
        if (player != null && hitResult != null
                && player.getMainHandItem().getItem() instanceof BlockItem
                && CrythstFabric.config() != null
                && CrythstFabric.config().enabled
                && StructurePlacementOcclusion.obstructed(
                        ClientStructureCollisionSource.of(player.level()),
                        player.level(), hitResult)) {
            callback.setReturnValue(InteractionResult.FAIL);
        }
    }
}
