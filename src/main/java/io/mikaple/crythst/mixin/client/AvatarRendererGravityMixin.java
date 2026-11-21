package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.player.PlayerRenderFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rotates a player model into the directional-gravity feet frame.
 *
 * <p>The pose stack at the head of {@code LivingEntityRenderer.setupRotations}
 * is already translated to the entity's render position (its feet, including
 * {@code getRenderOffset}) and scaled by {@code state.scale}. Right-multiplying
 * by {@code rotation(Q)} therefore appends a world-space rotation whose fixed
 * point is exactly that feet anchor:</p>
 *
 * <pre>
 * pose = T_feet * S(state.scale) * Q * R_bodyYaw
 *        * S(-1, -1, 1) * S_model * T(0, -1.501, 0)
 * </pre>
 *
 * <p>The {@code (-1, -1, 1)} flip and the {@code -1.501} translation are
 * <em>inside</em> {@code Q} (to its right), so they only place the model in the
 * upright frame; they cannot move the rotation pivot. This is the same feet
 * anchor that {@link io.mikaple.crythst.integration.minecraft.player.PlayerObb}
 * uses ({@code position()}) and that the camera eye is rotated about.</p>
 *
 * <p>Held items and armour are layers submitted inside the same pose stack, so
 * they follow automatically. The name tag is submitted after the model stack is
 * popped; {@code AvatarNameTagGravityMixin} rotates its attachment about the
 * same feet anchor.</p>
 *
 * <p>{@code setupRotations} is declared on {@code LivingEntityRenderer}, not on
 * {@code AvatarRenderer}, so this mixin targets the declaring class and filters
 * to {@link AvatarRenderState}. This avoids relying on inherited-method target
 * resolution and guarantees the injection lands on the method that actually
 * runs.</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class AvatarRendererGravityMixin {
    @Inject(method = "setupRotations", at = @At("HEAD"))
    private void crythst$rotateAvatarToGravity(LivingEntityRenderState state, PoseStack pose,
                                               float yaw, float pitch, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) {
            return;
        }
        final Quaterniond rotation = crythst$rotation(avatar);
        if (rotation == null) {
            return;
        }
        // The pose origin is the feet anchor. Do not add a half-height pivot:
        // the collision OBB, the eye and the avatar all rotate around the feet.
        pose.rotate(PlayerRenderFrame.toFloat(rotation));
    }

    /**
     * @return the gravity rotation for this avatar, or {@code null} when the
     *         avatar must stay in the vanilla frame
     */
    @Unique
    private static Quaterniond crythst$rotation(AvatarRenderState state) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || state == null) {
            return null;
        }
        final Entity entity = minecraft.level.getEntity(state.id);
        if (!(entity instanceof Player player) || !PlayerGravity.hasDirectionalGravity(player)) {
            return null;
        }
        return PlayerGravity.viewRotation(player);
    }
}
