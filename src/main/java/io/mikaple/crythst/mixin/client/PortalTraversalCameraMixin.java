package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.client.render.portal.PortalCrossingController;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.portal.PortalTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class PortalTraversalCameraMixin {
    @Inject(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void crythst$keepEntryCameraUntilTheEyeCrosses(DeltaTracker deltaTracker, CallbackInfo ci) {
        final Camera camera = (Camera) (Object) this;
        final Minecraft minecraft = Minecraft.getInstance();
        if (camera != minecraft.gameRenderer.mainCamera()
                || !(camera.entity() instanceof LocalPlayer player)
                || player != minecraft.player || !minecraft.options.getCameraType().isFirstPerson()
                || PlayerGravity.hasDirectionalGravity(player)) {
            return;
        }
        final Vec3 cameraPosition = camera.position();
        final PortalTransform entry = PortalCrossingController.entryCameraTransform(player, cameraPosition);
        if (entry == null) {
            return;
        }
        final Vector3d position = entry.transformPoint(
                new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
        final float[] angles = PortalTransform.yawPitch(entry.transformDirection(
                PortalTransform.viewDirection(camera.yRot(), camera.xRot())));
        final PortalCameraAccessor accessor = (PortalCameraAccessor) camera;
        accessor.crythst$setPosition(new Vec3(position.x, position.y, position.z));
        accessor.crythst$setRotation(angles[0], angles[1]);
    }
}
