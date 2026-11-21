package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla axis-aligned hitbox for players using directional
 * gravity; {@code PlayerObbDebugRenderer} draws their oriented box instead.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {
    @Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
    private void crythst$skipAxisAlignedHitbox(Entity entity, float partialTicks,
                                               boolean isServerEntity, CallbackInfo ci) {
        if (entity instanceof Player player && PlayerGravity.hasDirectionalGravity(player)) {
            ci.cancel();
        }
    }
}
