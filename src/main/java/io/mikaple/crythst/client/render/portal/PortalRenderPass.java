package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import io.mikaple.crythst.mixin.client.PortalLevelRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/**
 * One isolated RenderPearl destination render into a portal view target.
 *
 * <p>No raw OpenGL and no stencil: the normal {@link LevelRenderer} framegraph
 * is imported into the portal target, with a temporary
 * {@code GameRenderer.mainRenderTarget()} redirect. The destination camera uses
 * an oblique projection whose near plane is the exit portal plane, so geometry
 * behind the portal is clipped and cannot leak through the opening. Fog is
 * computed at the destination camera rather than copied from the main camera.</p>
 *
 * <p>All frame-global state (model-view, scissor, projection, fog, global
 * camera uniform) is saved and restored. The pass is synchronous on the render
 * thread and never re-enters itself; the surface renderer skips portal quads
 * while {@link #capturing()} is true, which also prevents recursive capture
 * explosions.</p>
 */
final class PortalRenderPass {
    private CrossFrameResourcePool resourcePool;
    private FogRenderer fogRenderer;
    private GlobalSettingsUniform globalSettings;
    private ProjectionMatrixBuffer projectionBuffer;

    private volatile RenderTarget targetOverride;
    private boolean capturing;

    void ensureResources() {
        if (resourcePool == null) {
            resourcePool = new CrossFrameResourcePool(3);
        }
        if (fogRenderer == null) {
            fogRenderer = new FogRenderer();
        }
        if (globalSettings == null) {
            globalSettings = new GlobalSettingsUniform();
        }
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("crythst portal");
        }
    }

    boolean capturing() {
        return capturing;
    }

    RenderTarget targetOverride() {
        return targetOverride;
    }

    void beginRound() {
        capturing = true;
    }

    void endRound() {
        capturing = false;
        targetOverride = null;
        if (fogRenderer != null) {
            fogRenderer.endFrame();
        }
    }

    double render(Minecraft minecraft, LevelRenderer levelRenderer,
                  LevelRenderState levelState, CameraRenderState mainCamera,
                  PortalViewCamera view, CameraRenderState portalCamera,
                  PortalViewTarget target, Camera camera, ClientLevel level,
                  DeltaTracker deltaTracker) {
        ensureResources();
        if (resourcePool == null || fogRenderer == null || globalSettings == null
                || projectionBuffer == null || target.target() == null) {
            return 0.0;
        }

        final int renderDistance = Math.max(2,
                minecraft.options.getEffectiveRenderDistance());
        final FogData fog = fogRenderer.setupFog(
                camera, renderDistance, deltaTracker, 0.0f, level);
        fogRenderer.updateBuffer(fog);
        final GpuBufferSlice fogSlice = fogRenderer.getBuffer(FogRenderer.FogMode.WORLD);
        final Vector4f fogColor = fog.color == null
                ? new Vector4f(0.0f, 0.0f, 0.0f, 1.0f) : new Vector4f(fog.color);
        if (portalCamera != null) {
            portalCamera.fogData = fog;
        }

        final org.joml.Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        final ScissorState savedScissor =
                new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        final GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        final ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        final GpuBufferSlice savedFog = RenderSystem.getShaderFog();
        final GpuBuffer savedGlobal = RenderSystem.getGlobalSettingsUniform();

        modelViewStack.pushMatrix();
        modelViewStack.identity();
        RenderSystem.disableScissorForRenderTypeDraws();
        RenderSystem.setProjectionMatrix(
                projectionBuffer.getBuffer(view.projection()), ProjectionType.PERSPECTIVE);
        RenderSystem.setShaderFog(fogSlice);
        updateGlobalSettings(minecraft, levelState, view.position(), target);

        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.target().getColorTexture(),
                new Vector4f(fogColor.x, fogColor.y, fogColor.z, 1.0f),
                target.target().getDepthTexture(), 0.0);

        final PortalLevelRendererAccessor levelRendererAccess =
                (PortalLevelRendererAccessor) levelRenderer;
        final SkyRenderer savedSkyRenderer = levelRendererAccess.crythst$getSkyRenderer();
        SkyRenderer portalSkyRenderer = null;
        try {
            portalSkyRenderer = target.skyRenderer(
                    minecraft.getTextureManager(), minecraft.getAtlasManager());
        } catch (Throwable ignored) {
            // Sky is non-critical; the destination fog colour remains.
        }
        if (portalSkyRenderer != null) {
            levelRendererAccess.crythst$setSkyRenderer(portalSkyRenderer);
        }

        final long start = System.nanoTime();
        targetOverride = target.target();
        try {
            levelRenderer.render(resourcePool, false, portalCamera,
                    fogSlice, fogColor, portalSkyRenderer != null, false);
        } finally {
            targetOverride = null;
            levelRendererAccess.crythst$setSkyRenderer(savedSkyRenderer);
            modelViewStack.popMatrix();
            restoreScissor(savedScissor);
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
            RenderSystem.setShaderFog(savedFog);
            if (savedGlobal != null) {
                RenderSystem.setGlobalSettingsUniform(savedGlobal);
            }
        }
        return (System.nanoTime() - start) / 1.0e6;
    }

    private void updateGlobalSettings(Minecraft minecraft, LevelRenderState levelState,
                                      Vec3 cameraPos, PortalViewTarget target) {
        final GameRenderState game = minecraft.gameRenderer.gameRenderState();
        final OptionsRenderState options = game.optionsRenderState;
        final WindowRenderState window = game.windowRenderState;
        final int width = target.width() > 0 ? target.width() : window.width;
        final int height = target.height() > 0 ? target.height() : window.height;
        globalSettings.update(
                width,
                height,
                options.glintStrength,
                levelState.gameTime,
                levelState.worldPartialTicks,
                options.menuBackgroundBlurriness,
                cameraPos,
                options.textureFiltering == TextureFilteringMethod.RGSS);
    }

    private static void restoreScissor(ScissorState state) {
        if (state != null && state.enabled()) {
            RenderSystem.enableScissorForRenderTypeDraws(
                    state.x(), state.y(), state.width(), state.height());
        } else {
            RenderSystem.disableScissorForRenderTypeDraws();
        }
    }

    void close() {
        capturing = false;
        targetOverride = null;
        if (fogRenderer != null) {
            fogRenderer.close();
            fogRenderer = null;
        }
        if (globalSettings != null) {
            globalSettings.close();
            globalSettings = null;
        }
        if (projectionBuffer != null) {
            projectionBuffer.close();
            projectionBuffer = null;
        }
        if (resourcePool != null) {
            resourcePool.clear();
            resourcePool = null;
        }
    }
}
