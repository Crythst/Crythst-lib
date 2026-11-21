package io.mikaple.crythst.mixin;

import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies a per-player gravity direction around vanilla's downward gravity.
 *
 * <p>Vanilla adds {@code -g} to the Y component inside {@code travelInAir}. At
 * the end of that method we add {@code (direction - (0,-1,0)) * g}, which
 * cancels the vertical term for non-default directions and injects the desired
 * X/Y/Z acceleration. With no override (the default) the delta is zero, so
 * vanilla behaviour is bit-for-bit unchanged.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {
    @Inject(method = "travelInAir", at = @At("RETURN"))
    private void crythst$applyPlayerGravityDirection(Vec3 input, CallbackInfo ci) {
        if (!((Object) this instanceof Player player)) {
            return;
        }
        if (player.getAbilities().flying) {
            return;
        }

        final Vector3d direction = PlayerGravity.direction(player);
        if (direction == null) {
            return;
        }
        final double gravity = player.getGravity();
        if (gravity == 0.0) {
            return;
        }

        final Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(
                motion.x + direction.x * gravity,
                motion.y + (direction.y + 1.0) * gravity,
                motion.z + direction.z * gravity
        );
    }
}
