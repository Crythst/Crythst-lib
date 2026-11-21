package io.mikaple.crythst.mixin.player;

import io.mikaple.crythst.integration.minecraft.player.PlayerObbCollision;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side local prediction for the player OBB correction.
 *
 * <p>This runs after {@code LocalPlayer.tick} so the client has already applied
 * its own movement. Terrain penetration is resolved locally; physics structures
 * are only known to the server and are corrected by the server mixin.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerObbMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void crythst$playerObbCollision(CallbackInfo ci) {
        PlayerObbCollision.tick((Player) (Object) this);
    }
}
