package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.client.render.portal.PortalCapture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the shared terrain {@code ViewArea} centred on the main camera during
 * a portal capture.
 *
 * <p>{@code LevelRenderer.render} normally re-centres the rotating section
 * storage on the camera it is about to render. Doing that for the portal camera
 * calls {@code RotatingSectionStorage.setSectionNode()}, which resets the
 * affected section meshes to {@code UNCOMPILED}. Bouncing the centre to the
 * destination and back every capture therefore made the destination texture
 * blank (meshes reset immediately before the draw) and caused visible main-view
 * stutter. The portal capture selects sections from the existing main-camera
 * storage instead and only updates the section dispatcher's camera position for
 * translucent sorting, so this re-centre is skipped while capturing.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class PortalLevelRendererMixin {
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void crythst$keepMainViewAreaForPortalCapture(CameraRenderState camera, CallbackInfo ci) {
        if (PortalCapture.isCapturing()) {
            ci.cancel();
        }
    }
}
