package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rotates a player model into the directional-gravity frame.
 *
 * <p>The model's local +Y is vanilla up. Left-multiplying the pose by the
 * quaternion that maps world up to the player's up rotates the whole avatar
 * around its feet, matching the camera, collision OBB and rendered world.</p>
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererGravityMixin {
    @Inject(method = "setupRotations", at = @At("HEAD"))
    private void crythst$rotateAvatarToGravity(AvatarRenderState state, PoseStack pose,
                                               float yaw, float pitch, CallbackInfo ci) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || state == null) {
            return;
        }
        final Entity entity = minecraft.level.getEntity(state.id);
        if (!(entity instanceof Player player) || !PlayerGravity.hasDirectionalGravity(player)) {
            return;
        }
        final Quaterniond rotation = PlayerGravity.viewRotation(player);
        pose.mulPose(new Matrix4f().rotation(new Quaternionf(
                (float) rotation.x, (float) rotation.y,
                (float) rotation.z, (float) rotation.w)));
    }
}
