package io.mikaple.crythst.mixin;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Maps vanilla WASD input into the player's directional-gravity frame.
 *
 * <p>Vanilla {@link Entity#moveRelative(float, Vec3)} rotates {@code (xxa, yya,
 * zza)} around world {@code +Y}; it therefore keeps moving along world-horizontal
 * axes even when the camera/avatar are rotated to a different gravity up. This
 * injection replaces that rotation for players with directional gravity:</p>
 *
 * <pre>
 * up      = PlayerGravity.up(player)
 * forward = normalize((Q * look) - up * dot(Q * look, up))
 * right   = forward x up
 * delta   = forward * input.z - right * input.x
 * </pre>
 *
 * <p>The {@code -right} sign is deliberate: vanilla {@code xxa} is positive
 * when strafing left (A = {@code +1}, D = {@code -1}), so using {@code +right}
 * would invert A/D. The input is normalized and scaled with the same rules as
 * {@code Entity.getInputVector}, and the Y component is left alone so jumping
 * and fluid/vertical motion are unaffected.</p>
 *
 * <p>Creative flight, riding a vehicle and the disabled camera-gravity
 * setting keep the vanilla path. Gravity acceleration is still applied by
 * {@code LivingEntityTravelMixin}; this mixin only changes the input delta
 * before vanilla {@code moveRelative} would add it.</p>
 */
@Mixin(Entity.class)
public abstract class PlayerGravityMovementMixin {
    @Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
    private void crythst$gravityRelativeMovement(float speed, Vec3 input, CallbackInfo ci) {
        if (!((Object) this instanceof Player player)) {
            return;
        }
        if (!PlayerGravity.hasDirectionalGravity(player)) {
            return;
        }
        if (player.getAbilities().flying || player.isPassenger()) {
            return;
        }
        if (!CrythstFabric.config().playerCameraGravity) {
            return;
        }

        // Horizontal yaw forward of the camera in the gravity frame, used when
        // the gravity-frame look itself is parallel to up and has no usable
        // projection. Rotating the vanilla yaw forward by Q keeps the fallback
        // aligned with the camera's yaw/roll rather than with world axes.
        final double yaw = Math.toRadians(player.getYRot());
        final Vector3d fallbackForward = PlayerGravity.viewRotation(player).transform(
                new Vector3d(-Math.sin(yaw), 0.0, Math.cos(yaw)));

        final PlayerGravity.MovementFrame frame = PlayerGravity.movementFrame(
                PlayerGravity.lookDirection(player),
                PlayerGravity.up(player),
                fallbackForward);
        final Vector3d delta = PlayerGravity.movementInGravityFrame(
                new Vector3d(input.x, input.y, input.z), speed, frame);

        player.setDeltaMovement(player.getDeltaMovement().add(delta.x, delta.y, delta.z));
        ci.cancel();
    }
}
