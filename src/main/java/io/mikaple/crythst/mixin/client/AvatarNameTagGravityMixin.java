package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.player.PlayerRenderFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves an avatar's name-tag anchor into the directional-gravity feet frame.
 *
 * <p>The model pose is popped before the name tag is submitted, so the tag does
 * not inherit {@code Q} from {@code setupRotations}. The submitted tag is placed
 * at {@code entityPosition + attachment + (0, 0.5, 0)} in world space. With
 * directional gravity the whole anchor has to be rotated about the feet:
 * {@code entityPosition + Q * (attachment + (0, 0.5, 0))}. The vanilla
 * {@code +0.5} is applied after the attachment, so it must be included in the
 * rotation and then cancelled in the stored attachment.</p>
 *
 * <p>The text itself is billboarded later from
 * {@code CameraRenderState.orientation}, which already contains {@code Q};
 * rotating the pose here would double-rotate the billboard, so only the
 * attachment vector is changed (and restored after submission).</p>
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarNameTagGravityMixin {
    @Unique
    private Vec3 crythst$savedNameTagAttachment;

    @Inject(method = "submitNameDisplay", at = @At("HEAD"))
    private void crythst$rotateNameTagAnchor(AvatarRenderState state, PoseStack pose,
                                             SubmitNodeCollector collector,
                                             CameraRenderState camera, CallbackInfo ci) {
        final Quaterniond rotation = crythst$nameTagRotation(state);
        if (rotation == null || state.nameTagAttachment == null) {
            return;
        }
        final Vec3 attachment = state.nameTagAttachment;
        final Vector3d rotated = PlayerRenderFrame.nameTagAttachment(
                rotation, new Vector3d(attachment.x, attachment.y, attachment.z));
        this.crythst$savedNameTagAttachment = attachment;
        state.nameTagAttachment = new Vec3(rotated.x, rotated.y, rotated.z);
    }

    @Inject(method = "submitNameDisplay", at = @At("RETURN"))
    private void crythst$restoreNameTagAnchor(AvatarRenderState state, PoseStack pose,
                                              SubmitNodeCollector collector,
                                              CameraRenderState camera, CallbackInfo ci) {
        if (this.crythst$savedNameTagAttachment != null) {
            state.nameTagAttachment = this.crythst$savedNameTagAttachment;
            this.crythst$savedNameTagAttachment = null;
        }
    }

    @Unique
    private static Quaterniond crythst$nameTagRotation(AvatarRenderState state) {
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
