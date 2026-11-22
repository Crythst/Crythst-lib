package io.mikaple.crythst.mixin.explosion;

import io.mikaple.crythst.integration.minecraft.gameevent.StructureExplosions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Routes a completed vanilla explosion through the structure blast bridge.
 *
 * <p>{@code explode()} is invoked both for host-world and auxiliary-void
 * explosions. {@code StructureExplosions} selects exactly one mode from the
 * level: host mode destroys structure voxels with the vanilla ray walk, void
 * mode uses the already-destroyed copy, applies impulses and re-emits entity
 * damage to the host. This single dispatch is what prevents a host+void double
 * destruction pass.</p>
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Inject(method = "interactWithBlocks(Ljava/util/List;)V", at = @At("HEAD"))
    private void crythst$captureVoidBlockTargets(List<BlockPos> targetBlocks,
                                                 CallbackInfo callback) {
        StructureExplosions.captureVoidBlockTargets(
                (ServerExplosion) (Object) this, targetBlocks);
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void crythst$afterExplosion(CallbackInfoReturnable<Integer> callback) {
        StructureExplosions.afterVanillaExplosion((ServerExplosion) (Object) this);
    }
}
