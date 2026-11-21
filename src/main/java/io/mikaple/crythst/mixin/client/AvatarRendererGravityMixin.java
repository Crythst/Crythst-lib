package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rotates a player model into the directional-gravity frame.
 *
 * <p>The model's local {@code +Y} is vanilla up. At the head of
 * {@code LivingEntityRenderer.setupRotations} the pose stack is already
 * translated to the entity's feet (and scaled by {@code state.scale}), so
 * right-multiplying the current matrix by {@code rotation(Q)} appends a
 * world-space rotation applied <em>after</em> vanilla's body-yaw/pose
 * transforms. The whole avatar therefore turns about its feet into the gravity
 * frame:</p>
 *
 * <pre>pose = T_feet * Q * R_bodyYaw * ...</pre>
 *
 * <p>Held items and armour are layers submitted inside the same pose stack, so
 * they follow automatically. The name tag is submitted after the model stack is
 * popped; {@code AvatarNameTagGravityMixin} rotates its attachment offset.</p>
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
    private void crythst$rotateAvatarToGravity(EntityRenderState state, PoseStack pose,
                                               float yaw, float pitch, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) {
            return;
        }
        final Quaterniond rotation = crythst$rotation(avatar);
        if (rotation == null) {
            return;
        }
        // Right-multiply: the matrix already sits at the entity's feet, and the
        // resulting transform rotates the yawed model around that pivot in
        // world space (Q is applied after the vanilla local rotations).
        pose.mulPose(new Matrix4f().rotation(new Quaternionf(
                (float) rotation.x, (float) rotation.y,
                (float) rotation.z, (float) rotation.w)));
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
