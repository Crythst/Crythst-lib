package io.mikaple.crythst.mixin.player;

import io.mikaple.crythst.integration.minecraft.player.PlayerObbCollision;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the post-move OBB correction once per server player tick.
 *
 * <p>Injecting at the tail of {@code ServerPlayer.tick} means vanilla has
 * already run its AABB movement for the tick. Players with default gravity are
 * ignored inside {@link PlayerObbCollision#tick}.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerObbMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void crythst$playerObbCollision(CallbackInfo ci) {
        PlayerObbCollision.tick((Player) (Object) this);
    }
}
