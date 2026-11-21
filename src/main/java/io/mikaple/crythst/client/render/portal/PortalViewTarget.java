package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.TextureTarget;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.UUID;

/**
 * Per-portal offscreen destination view.
 *
 * <p>Owns the colour+depth target, the texture-manager wrapper, readiness and
 * the camera sample used to decide whether the current image can be reused.</p>
 */
final class PortalViewTarget {
    private final UUID portalId;
    private final Identifier textureId;
    private final PortalViewTexture texture;
    private TextureTarget target;
    private boolean ready;
    private long lastCaptureNanos;
    private Vec3 lastCameraPos;
    private float lastCameraYaw;
    private float lastCameraPitch;
    private final Matrix4f captureViewProjection = new Matrix4f();
    private Vec3 captureCameraPos;
    private SkyRenderer skyRenderer;

    PortalViewTarget(UUID portalId, Identifier textureId,
                     PortalViewTexture texture, TextureTarget target) {
        this.portalId = portalId;
        this.textureId = textureId;
        this.texture = texture;
        this.target = target;
    }

    UUID portalId() {
        return portalId;
    }

    Identifier textureId() {
        return textureId;
    }

    TextureTarget target() {
        return target;
    }

    int width() {
        return target == null ? 0 : target.width;
    }

    int height() {
        return target == null ? 0 : target.height;
    }

    boolean ready() {
        return ready && target != null && target.getColorTexture() != null;
    }

    boolean matches(int width, int height) {
        return target != null && target.width == width && target.height == height;
    }

    void replaceTarget(TextureTarget replacement,
                       com.mojang.renderpearl.api.textures.GpuSampler sampler) {
        ready = false;
        closeSkyRenderer();
        if (target != null) {
            target.destroyBuffers();
        }
        target = replacement;
        texture.setTarget(replacement, sampler);
        // Re-register the same wrapper so the manager cannot retain a stale
        // texture entry across a target resize.
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        PortalRenderTypes.invalidate(textureId);
    }

    /**
     * A SkyRenderer bound to this target. Created lazily and reused until the
     * target is resized/closed; it prevents the shared main-target sky renderer
     * from being used for the portal view.
     */
    SkyRenderer skyRenderer(TextureManager textureManager, AtlasManager atlasManager) {
        if (target == null) {
            return null;
        }
        if (skyRenderer == null) {
            skyRenderer = new SkyRenderer(textureManager, atlasManager, target);
        }
        return skyRenderer;
    }

    void markReady(CameraRenderState mainCamera, long now, Matrix4f viewProjection,
                   Vec3 captureCameraPosition) {
        ready = true;
        lastCaptureNanos = now;
        if (viewProjection != null) {
            captureViewProjection.set(viewProjection);
        }
        captureCameraPos = captureCameraPosition;
        if (mainCamera != null) {
            lastCameraPos = mainCamera.pos;
            lastCameraYaw = mainCamera.yRot;
            lastCameraPitch = mainCamera.xRot;
        }
    }

    Matrix4f captureViewProjection() {
        return new Matrix4f(captureViewProjection);
    }

    Vec3 captureCameraPos() {
        return captureCameraPos;
    }

    void markFailed() {
        ready = false;
    }

    boolean isReusable(CameraRenderState mainCamera, long now) {
        if (!ready || lastCaptureNanos == 0L || lastCameraPos == null
                || mainCamera == null || mainCamera.pos == null) {
            return false;
        }
        if (now - lastCaptureNanos > PortalCaptureSettings.REUSE_MAX_AGE_NANOS) {
            return false;
        }
        if (mainCamera.pos.distanceToSqr(lastCameraPos)
                > PortalCaptureSettings.REUSE_DISTANCE_SQUARED) {
            return false;
        }
        final float yawDelta = Math.abs(Mth.wrapDegrees(mainCamera.yRot - lastCameraYaw));
        final float pitchDelta = Math.abs(mainCamera.xRot - lastCameraPitch);
        return yawDelta <= PortalCaptureSettings.REUSE_ANGLE_DEGREES
                && pitchDelta <= PortalCaptureSettings.REUSE_ANGLE_DEGREES;
    }

    void close() {
        ready = false;
        closeSkyRenderer();
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    private void closeSkyRenderer() {
        if (skyRenderer != null) {
            skyRenderer.close();
            skyRenderer = null;
        }
    }

    static TextureTarget createTarget(PortalSnapshot portal, int width, int height,
                                      com.mojang.renderpearl.api.GpuFormat colorFormat,
                                      com.mojang.renderpearl.api.GpuFormat depthFormat) {
        return new TextureTarget("crythst_portal_view_" + portal.id(),
                width, height, colorFormat, depthFormat);
    }
}
