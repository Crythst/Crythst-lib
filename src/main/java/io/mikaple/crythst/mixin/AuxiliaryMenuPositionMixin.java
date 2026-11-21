package io.mikaple.crythst.mixin;

import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps container menus opened from a structure voxel alive.
 *
 * <p>Vanilla menus validate themselves with
 * {@code Player#isWithinBlockInteractionRange(BlockPos, double)}. A block entity
 * copied into the auxiliary void level reports an auxiliary position, which is
 * usually far away from the player in the host world, so the menu would close
 * on the first server tick. This mixin maps only auxiliary positions back to
 * the structure's current host-world block before running the ordinary range
 * check; every real-world position keeps the vanilla fast path.</p>
 */
@Mixin(Player.class)
public abstract class AuxiliaryMenuPositionMixin {
    @Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void crythst$mapAuxiliaryMenuPosition(BlockPos pos, double buffer,
                                                  CallbackInfoReturnable<Boolean> callback) {
        final Player player = (Player) (Object) this;
        final BlockPos hostPos = EmptyPhysicsLevel.mapAuxiliaryToHost(player, pos);
        if (hostPos == null) {
            return;
        }
        final double maxRange = player.blockInteractionRange() + buffer;
        callback.setReturnValue(new AABB(hostPos).distanceToSqr(player.getEyePosition())
                < maxRange * maxRange);
    }
}
