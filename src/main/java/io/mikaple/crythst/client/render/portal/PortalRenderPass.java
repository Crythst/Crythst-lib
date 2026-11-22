package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.mikaple.crythst.client.render.structure.batch.BatchedStructureRenderer;
import io.mikaple.crythst.client.render.structure.blockentity.StructureBlockEntityRenderer;
import io.mikaple.crythst.client.render.structure.moving.StructureMovingBlockRenderer;
import io.mikaple.crythst.integration.fabric.client.StructureBlockRenderer;
import io.mikaple.crythst.mixin.client.PortalCameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.OptionalDouble;

/**
 * The same-FBO destination render pass.
 *
 * <p>For each extracted layer the pass:</p>
 * <ol>
 *     <li>saves the current main colour+depth into a per-layer snapshot,</li>
 *     <li>rasterizes the clipped opening polygon into a per-layer mask,</li>
 *     <li>clears only the portal's screen rectangle (colour+depth) and renders
 *     the destination sky, terrain, entities, block entities, particles,
 *     weather, clouds and world border directly into the main framebuffer,</li>
 *     <li>composites the snapshot back outside the mask, and</li>
 *     <li>restores the snapshot depth.</li>
 * </ol>
 *
 * <p>The destination world draw is scissored to the portal's screen bounding
 * rectangle. The sky renderer creates its own full-screen pass, which is why a
 * full-frame colour snapshot is kept; the composite restores everything outside
 * the opening.</p>
 */
final class PortalRenderPass {
    private static final Vector4f ZERO = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    private static final int MASK_BUFFER_BYTES = 16 * 1024;

    private FogRenderer fogRenderer;
    private GlobalSettingsUniform globalSettings;
    private ProjectionMatrixBuffer projectionBuffer;
    /**
     * Dedicated dispatcher + buffers: the main frame's single PreparedFrame is
     * already in use while this framegraph pass executes, so reusing
     * {@code gameRenderer.featureRenderDispatcher()} throws
     * "PreparedFrame already in use".
     */
    private FeatureRenderDispatcher featureDispatcher;
    private RenderBuffers featureBuffers;
    private GpuBuffer maskBuffer;
    private int maskBufferBytes;
    private final List<LayerTargets> layerTargets = new ArrayList<>();
    private final List<PortalCloudRenderer> portalClouds = new ArrayList<>();
    private boolean failureLogged;
    /** True when a layer-1 portal drew its opening, so the final occlusion depth is valid. */
    private boolean renderedLayerOne;

    /**
     * Adds the portal pass and returns the newest main-target handle. The
     * caller must store it back into {@code LevelTargetBundle.main}; otherwise
     * later framegraph passes would use an invalidated handle version.
     */
    ResourceHandle<RenderTarget> addPass(FrameGraphBuilder frame, ResourceHandle<RenderTarget> main,
                                         List<PortalRenderData> data) {
        final FramePass pass = frame.addPass("crythst_portals");
        pass.disableCulling();
        final ResourceHandle<RenderTarget> updated = pass.readsAndWrites(main);
        pass.executes(() -> render(data));
        return updated;
    }

    private void ensureResources() {
        if (fogRenderer == null) {
            fogRenderer = new FogRenderer();
        }
        if (globalSettings == null) {
            globalSettings = new GlobalSettingsUniform();
        }
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("crythst portal");
        }
        if (featureDispatcher == null) {
            final Minecraft minecraft = Minecraft.getInstance();
            featureBuffers = new RenderBuffers(4);
            featureDispatcher = new FeatureRenderDispatcher(
                    featureBuffers,
                    minecraft.getModelManager(),
                    minecraft.getAtlasManager(),
                    minecraft.font,
                    minecraft.gameRenderer.gameRenderState());
        }
        if (maskBuffer == null) {
            maskBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Crythst portal mask vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    MASK_BUFFER_BYTES);
            maskBufferBytes = MASK_BUFFER_BYTES;
        }
    }

    private void render(List<PortalRenderData> data) {
        final Minecraft minecraft = Minecraft.getInstance();
        final RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        if (main == null || main.getColorTexture() == null || main.getDepthTexture() == null
                || main.width <= 0 || main.height <= 0) {
            return;
        }
        ensureResources();
        if (fogRenderer == null || globalSettings == null || projectionBuffer == null
                || maskBuffer == null) {
            return;
        }

        final Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        final GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        final ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        final GpuBufferSlice savedFog = RenderSystem.getShaderFog();
        final GpuBuffer savedGlobal = RenderSystem.getGlobalSettingsUniform();

        modelViewStack.pushMatrix();
        try {
            renderedLayerOne = false;
            final Set<Integer> failedLayers = new HashSet<>();
            for (int index = 0; index < data.size(); index++) {
                final PortalRenderData layer = data.get(index);
                if (!layer.visible()) {
                    continue;
                }
                // A child composite multiplies its mask by the parent's mask.
                // If the parent layer failed, its mask may be empty or stale,
                // so rendering the child could paint outside the outer opening.
                // Skip the whole subtree for this frame.
                if (layer.parentLayer() > 0 && failedLayers.contains(layer.parentLayer())) {
                    failedLayers.add(layer.layer());
                    continue;
                }
                try {
                    renderLayer(minecraft, main, layer, index);
                } catch (Throwable error) {
                    failedLayers.add(layer.layer());
                    if (!failureLogged) {
                        failureLogged = true;
                        PortalRenderDiagnostics.failure(
                                "Crythst same-FBO portal pass failed; skipping frame", error);
                    }
                }
            }
            if (renderedLayerOne) {
                restoreOcclusionDepth(main);
            }
        } finally {
            modelViewStack.popMatrix();
            if (savedProjection != null) {
                RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
            }
            RenderSystem.setShaderFog(savedFog);
            if (savedGlobal != null) {
                RenderSystem.setGlobalSettingsUniform(savedGlobal);
            }
            final ClientLevel level = minecraft.level;
            final Camera mainCamera = minecraft.gameRenderer.mainCamera();
            if (level != null && mainCamera != null) {
                final Vec3 mainPos = mainCamera.position();
                mainCamera.attributeProbe().reset();
                mainCamera.attributeProbe().tick(level, mainPos);
            }
            fogRenderer.endFrame();
            if (featureBuffers != null) {
                featureBuffers.endFrame();
            }
        }
    }

    private void discardFeatureDispatcher() {
        if (featureDispatcher != null) {
            featureDispatcher.close();
            featureDispatcher = null;
        }
        if (featureBuffers != null) {
            featureBuffers.close();
            featureBuffers = null;
        }
    }

    private void renderLayer(Minecraft minecraft, RenderTarget main, PortalRenderData layer, int index) {
        final ClientLevel level = minecraft.level;
        final LevelRenderer levelRenderer = minecraft.levelRenderer;
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        final LayerTargets targets = targetsFor(layer.layer(), main.width, main.height);
        final PortalMaskGeometry.Mask mask = layer.mask();
        if (targets == null || mask.isEmpty() || layer.chunkSections() == null) {
            return;
        }

        // 1. Snapshot the current main framebuffer.
        encoder.copyTextureToTexture(main.getColorTexture(), targets.snapshot.getColorTexture(),
                0, 0, 0, 0, 0, main.width, main.height);
        encoder.copyTextureToTexture(main.getDepthTexture(), targets.snapshot.getDepthTexture(),
                0, 0, 0, 0, 0, main.width, main.height);

        boolean completed = false;
        try {
        // 2. Build the opening mask, depth-tested against the pre-layer scene
        // depth. Only pixels where the portal plane is actually the visible
        // surface are marked.
        drawMask(encoder, targets, mask);
        // 2b. Clear depth to reverse-Z far only through the verified polygon.
        // Entry-world occluders outside the mask keep their depth until the
        // final masked depth restore.
        drawDepthClear(encoder, main, targets, layer);

        // 3. Set up destination camera/fog/projection. The real Camera is
        // temporarily aimed at the destination only so FogRenderer can use the
        // same attribute probe as a normal level render.
        final Camera realCamera = minecraft.gameRenderer.mainCamera();
        final PortalCameraAccessor cameraAccess = (PortalCameraAccessor) realCamera;
        final Vec3 savedPos = realCamera.position();
        final float savedYaw = realCamera.yRot();
        final float savedPitch = realCamera.xRot();
        cameraAccess.crythst$setPosition(layer.view().position());
        cameraAccess.crythst$setRotation(layer.view().yaw(), layer.view().pitch());
        realCamera.attributeProbe().reset();
        realCamera.attributeProbe().tick(level, layer.view().position());
        final DeltaTracker deltaTracker = minecraft.getDeltaTracker();
        final int renderDistance = Math.max(2, minecraft.options.getEffectiveRenderDistance());
        final FogData fog;
        try {
            fog = fogRenderer.setupFog(realCamera, renderDistance, deltaTracker, 0.0f, level);
            fogRenderer.updateBuffer(fog);
        } finally {
            cameraAccess.crythst$setPosition(savedPos);
            cameraAccess.crythst$setRotation(savedYaw, savedPitch);
        }
        final GpuBufferSlice fogSlice = fogRenderer.getBuffer(FogRenderer.FogMode.WORLD);
        final Vector4f fogColor = fog.color == null
                ? new Vector4f(0.0f, 0.0f, 0.0f, 1.0f) : new Vector4f(fog.color);
        layer.state().cameraRenderState.fogData = fog;

        modelViewStack().identity();
        modelViewStack().mul(layer.view().viewRotation());
        RenderSystem.setProjectionMatrix(
                projectionBuffer.getBuffer(layer.view().projection()),
                ProjectionType.PERSPECTIVE);
        RenderSystem.setShaderFog(fogSlice);
        updateGlobalSettings(minecraft, layer.state(), main);

        // 4. Fill colour with the destination fog. This is a full-frame clear,
        // but the composite restores every pixel outside the opening, so it
        // cannot leak. Depth is intentionally untouched here: the polygon
        // depth-clear above only opened the verified portal pixels, while the
        // final depth restore puts the pre-layer depth back outside the mask.
        encoder.clearColorTexture(main.getColorTexture(), fogColor);

        // 5. Destination sky. SkyRenderer creates its own full-screen pass, so
        // the snapshot/composite restores the screen outside the opening.
        final SkyRenderer skyRenderer = levelRenderer.skyRenderer();
        if (skyRenderer != null
                && layer.state().skyRenderState.skybox != DimensionType.Skybox.NONE) {
            skyRenderer.render(fogSlice, layer.state().skyRenderState);
        }

        // 6. Destination world into the main colour+depth attachments.
        renderDestinationWorld(minecraft, levelRenderer, layer, main, mask, index);

        // 7. Composite: keep destination pixels inside the opening, restore the
        // snapshot outside it. Layer 2 also multiplies by the parent mask.
        composite(minecraft, main, targets, layer);
        // 8. Restore snapshot depth outside the final mask only. Destination
        // depth survives inside the opening, which is what lets layer 2 be
        // occluded by layer 1's destination geometry.
        restoreDepth(encoder, main, targets, layer);
        // Records that layer 1 produced a valid opening: the final occlusion
        // depth restore below needs this layer's snapshot depth, which already
        // holds the portal plane depth through the rasterized mask polygon.
        if (layer.layer() == 1) {
            renderedLayerOne = true;
        }
        completed = true;
        } finally {
            if (!completed) {
                // The destination sky pass can overwrite the full screen before a
                // later step fails. Restore the pre-portal pixels so a failure
                // degrades to "no portal this frame", not "screen explosion".
                restoreSnapshot(encoder, main, targets);
            }
        }
    }

    /**
     * Restores the layer-1 snapshot depth over the whole frame once every layer
     * has been composited.
     *
     * <p>Inside a layer-1 opening the snapshot depth already holds the rasterized
     * portal plane depth, because the mask pass renders the clipped polygon
     * against that attachment. Restoring it makes the later framegraph passes —
     * batched structures, moving blocks, block entities and gizmos run after this
     * pass — depth-test against the portal surface instead of the destination
     * world. Without it, entry-world geometry behind the portal plane still
     * passes the destination depth (a destination sky writes far) and is painted
     * on top of the portal image, which reads as structures flickering through
     * the opening. Outside the openings this only rewrites the entry-world depth
     * the snapshot already held, so it is a no-op there.</p>
     */
    private void restoreOcclusionDepth(RenderTarget main) {
        final LayerTargets targets = targetsFor(1, main.width, main.height);
        if (targets == null || targets.snapshot == null) {
            return;
        }
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    targets.snapshot.getDepthTexture(), main.getDepthTexture(),
                    0, 0, 0, 0, 0, main.width, main.height);
        } catch (Throwable error) {
            PortalRenderDiagnostics.failure(
                    "Crythst portal pass could not restore the occlusion depth", error);
        }
    }

    private static void restoreSnapshot(CommandEncoder encoder, RenderTarget main,
                                        LayerTargets targets) {
        try {
            encoder.copyTextureToTexture(targets.snapshot.getColorTexture(),
                    main.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            encoder.copyTextureToTexture(targets.snapshot.getDepthTexture(),
                    main.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        } catch (Throwable restoreError) {
            PortalRenderDiagnostics.failure(
                    "Crythst portal pass could not restore the main framebuffer",
                    restoreError);
        }
    }

    private void renderDestinationWorld(Minecraft minecraft, LevelRenderer levelRenderer,
                                        PortalRenderData layer, RenderTarget main,
                                        PortalMaskGeometry.Mask mask, int index) {
        final LevelRenderState state = layer.state();
        final OptionsRenderState options = minecraft.gameRenderer
                .gameRenderState().optionsRenderState;
        FeatureRenderDispatcher.PreparedFrame featureFrame = null;
        PortalCloudRenderer cloudRenderer = null;
        try {
            if (featureDispatcher == null) {
                // Rebuild after a failed prepare on a previous layer/frame.
                ensureResources();
            }
            final SubmitNodeStorage submits = new SubmitNodeStorage();
            submits.setUseImprovedTransparency(false);
            submitFeatures(minecraft, levelRenderer, layer, submits);
            try {
                featureFrame = featureDispatcher.prepareFrame(submits);
            } catch (Throwable error) {
                // prepareFrame checks out the dispatcher's single PreparedFrame
                // before doing the actual prepare work. If that work throws, no
                // PreparedFrame was returned to close, so the dispatcher would
                // otherwise stay poisoned ("PreparedFrame already in use") for
                // the rest of the session. Drop and rebuild it next frame.
                discardFeatureDispatcher();
                throw error;
            }

            if (options.cloudStatus != CloudStatus.OFF && ARGB.alpha(state.cloudColor) > 0) {
                cloudRenderer = cloudsFor(index, minecraft.getResourceManager());
                cloudRenderer.prepare(state.cloudColor, options.cloudStatus, state.cloudHeight,
                        options.cloudRange, state.cameraRenderState.pos,
                        state.gameTime, state.worldPartialTicks);
            }
            final int renderDistance = options.renderDistance * 16;
            levelRenderer.worldBorderRenderer().prepare(
                    state.worldBorderRenderState, state.cameraRenderState.pos,
                    renderDistance, state.cameraRenderState.depthFar);
            levelRenderer.weatherEffectRenderer().prepare(
                    state.cameraRenderState.pos, state.weatherRenderState);

            // Prepared once during extraction with the dedicated portal section
            // storage; no prepareChunkRenders() in the framegraph pass.
            final ChunkSectionsToRender chunkSections = layer.chunkSections();
            final GpuSampler sampler = RenderSystem.getSamplerCache()
                    .getClampToEdge(FilterMode.LINEAR);
            final var blockAtlas = minecraft.getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            final RenderPass.RenderArea area = new RenderPass.RenderArea(
                    mask.minX(), mask.minY(), mask.width(), mask.height());

            final RenderPassDescriptor descriptor = RenderPassDescriptor.builder(
                            () -> "Crythst portal destination")
                    .withColorAttachment(main.getColorTextureView())
                    .withDepthAttachment(main.getDepthTextureView())
                    .withRenderArea(area)
                    .build();
            try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder().createRenderPass(descriptor)) {
                RenderSystem.bindDefaultUniforms(renderPass);
                chunkSections.renderGroup(ChunkSectionLayerGroup.OPAQUE, renderPass,
                        sampler, blockAtlas, state.renderWireframeTerrain);
                featureFrame.executeSolid(renderPass);
                featureFrame.executeTranslucent(renderPass);
                chunkSections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, renderPass,
                        sampler, blockAtlas, state.renderWireframeTerrain);
                featureFrame.executeTranslucentAfterTerrain(renderPass);
                if (cloudRenderer != null) {
                    cloudRenderer.render(options.cloudStatus, renderPass);
                }
                levelRenderer.weatherEffectRenderer().render(state.weatherRenderState, renderPass);
                levelRenderer.worldBorderRenderer().render(
                        state.worldBorderRenderState, renderPass,
                        state.cameraRenderState.pos, renderDistance);
            }
        } finally {
            if (cloudRenderer != null) {
                cloudRenderer.endFrame();
            }
            if (featureFrame != null) {
                featureFrame.close();
            }
        }
    }

    private static void submitFeatures(Minecraft minecraft, LevelRenderer levelRenderer,
                                       PortalRenderData layer, SubmitNodeCollector collector) {
        final LevelRenderState state = layer.state();
        final PoseStack poseStack = new PoseStack();
        final CameraRenderState camera = state.cameraRenderState;
        final Vec3 cameraPos = camera.pos;
        final double camX = cameraPos.x;
        final double camY = cameraPos.y;
        final double camZ = cameraPos.z;
        for (var entity : state.entityRenderStates) {
            levelRenderer.entityRenderDispatcher().submit(entity, camera,
                    entity.x - camX, entity.y - camY, entity.z - camZ,
                    poseStack, collector);
        }
        for (var blockEntity : state.blockEntityRenderStates) {
            poseStack.pushPose();
            poseStack.translate(blockEntity.blockPos.getX() - camX,
                    blockEntity.blockPos.getY() - camY,
                    blockEntity.blockPos.getZ() - camZ);
            levelRenderer.blockEntityRenderDispatcher().submit(
                    blockEntity, poseStack, collector, camera);
            poseStack.popPose();
        }
        state.particlesRenderState.submit(collector, camera);
        BatchedStructureRenderer.submitPortalView(minecraft, camera, collector);
        StructureBlockRenderer.submit(minecraft, camera, collector);
        try {
            StructureBlockEntityRenderer.submit(minecraft, camera, poseStack, collector);
        } finally {
            levelRenderer.blockEntityRenderDispatcher().prepare(minecraft.gameRenderer.mainCamera().position());
        }
        StructureMovingBlockRenderer.submit(minecraft, camera, poseStack, collector);
        if (layer.hitboxes() != null) {
            layer.hitboxes().submit(collector, camera, false);
        }
    }

    private void drawMask(CommandEncoder encoder, LayerTargets targets,
                          PortalMaskGeometry.Mask mask) {
        encoder.clearColorTexture(targets.mask.getColorTexture(), ZERO);
        final int bytes = mask.triangles().length * Float.BYTES;
        if (bytes <= 0 || bytes > maskBufferBytes) {
            return;
        }
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for (float value : mask.triangles()) {
            buffer.putFloat(value);
        }
        buffer.flip();
        encoder.writeToBuffer(maskBuffer.slice(0, bytes), buffer);

        final RenderPassDescriptor descriptor = RenderPassDescriptor.builder(
                        () -> "Crythst portal mask")
                .withColorAttachment(targets.mask.getColorTextureView())
                .withDepthAttachment(targets.snapshot.getDepthTextureView())
                .withRenderArea(new RenderPass.RenderArea(
                        mask.minX(), mask.minY(), mask.width(), mask.height()))
                .build();
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder().createRenderPass(descriptor)) {
            renderPass.setPipeline(RenderSystem.getCompiledPipeline(
                    PortalPassRenderTypes.mask()));
            renderPass.setVertexBuffer(0, maskBuffer.slice(0, bytes));
            renderPass.draw(mask.vertexCount(), 1, 0, 0);
        }
    }

    /**
     * Writes reverse-Z far depth through the already-verified opening polygon.
     * Pixels hidden by terrain or by a parent mask keep their depth.
     */
    private void drawDepthClear(CommandEncoder encoder, RenderTarget main,
                                LayerTargets targets, PortalRenderData layer) {
        final GpuTextureView parentMask = parentMask(main, targets, layer);
        final GpuSampler nearest = RenderSystem.getSamplerCache()
                .getClampToEdge(FilterMode.NEAREST);
        final RenderPassDescriptor descriptor = RenderPassDescriptor.builder(
                        () -> "Crythst portal depth clear")
                .withColorAttachment(main.getColorTextureView())
                .withDepthAttachment(main.getDepthTextureView())
                .build();
        try (RenderPass renderPass = encoder.createRenderPass(descriptor)) {
            renderPass.setPipeline(RenderSystem.getCompiledPipeline(
                    PortalPassRenderTypes.depthClear()));
            renderPass.setUniform("Sampler0", targets.mask.getColorTextureView(), nearest);
            renderPass.setUniform("Sampler1", parentMask, nearest);
            renderPass.draw(3, 1, 0, 0);
        }
    }

    /**
     * Restores snapshot depth only outside the combined final mask. Inside the
     * opening the destination depth is kept, so a child layer is occluded by
     * parent-layer geometry instead of always clearing the rectangle.
     */
    private void restoreDepth(CommandEncoder encoder, RenderTarget main,
                              LayerTargets targets, PortalRenderData layer) {
        final GpuTextureView parentMask = parentMask(main, targets, layer);
        final GpuSampler nearest = RenderSystem.getSamplerCache()
                .getClampToEdge(FilterMode.NEAREST);
        final RenderPassDescriptor descriptor = RenderPassDescriptor.builder(
                        () -> "Crythst portal depth restore")
                .withColorAttachment(main.getColorTextureView())
                .withDepthAttachment(main.getDepthTextureView())
                .build();
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder().createRenderPass(descriptor)) {
            renderPass.setPipeline(RenderSystem.getCompiledPipeline(
                    PortalPassRenderTypes.depthRestore()));
            renderPass.setUniform("Sampler0",
                    targets.snapshot.getDepthTextureView(), nearest);
            renderPass.setUniform("Sampler1", targets.mask.getColorTextureView(), nearest);
            renderPass.setUniform("Sampler2", parentMask, nearest);
            renderPass.draw(3, 1, 0, 0);
        }
    }

    private GpuTextureView parentMask(RenderTarget main, LayerTargets targets,
                                      PortalRenderData layer) {
        final LayerTargets parent = layer.parentLayer() > 0
                ? targetsFor(layer.parentLayer(), main.width, main.height) : targets;
        return parent != null && parent != targets
                ? parent.mask.getColorTextureView() : targets.mask.getColorTextureView();
    }

    private void composite(Minecraft minecraft, RenderTarget main, LayerTargets targets,
                           PortalRenderData layer) {
        final var parentMask = parentMask(main, targets, layer);
        final GpuSampler nearest = RenderSystem.getSamplerCache()
                .getClampToEdge(FilterMode.NEAREST);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Crythst portal composite",
                        main.getColorTextureView(), Optional.empty(), null,
                        OptionalDouble.empty())) {
            renderPass.setPipeline(RenderSystem.getCompiledPipeline(
                    PortalPassRenderTypes.composite()));
            renderPass.setUniform("Sampler0",
                    targets.snapshot.getColorTextureView(), nearest);
            renderPass.setUniform("Sampler1", targets.mask.getColorTextureView(), nearest);
            renderPass.setUniform("Sampler2", parentMask, nearest);
            renderPass.draw(3, 1, 0, 0);
        }
    }

    private LayerTargets targetsFor(int layer, int width, int height) {
        final int index = Math.max(0, layer - 1);
        while (layerTargets.size() <= index) {
            layerTargets.add(new LayerTargets());
        }
        final LayerTargets targets = layerTargets.get(index);
        targets.ensure(width, height);
        return targets;
    }

    private Matrix4fStack modelViewStack() {
        return RenderSystem.getModelViewStack();
    }

    private void updateGlobalSettings(Minecraft minecraft, LevelRenderState state,
                                      RenderTarget target) {
        final var game = minecraft.gameRenderer.gameRenderState();
        final OptionsRenderState options = game.optionsRenderState;
        globalSettings.update(
                target.width,
                target.height,
                options.glintStrength,
                state.gameTime,
                state.worldPartialTicks,
                options.menuBackgroundBlurriness,
                state.cameraRenderState.pos,
                options.textureFiltering == TextureFilteringMethod.RGSS);
    }

    private PortalCloudRenderer cloudsFor(int index, ResourceManager resources) {
        // Several portals can share a recursion depth. Each draw needs its
        // own cloud buffers because all draws are in the same GPU submission.
        while (portalClouds.size() <= index) {
            portalClouds.add(null);
        }
        PortalCloudRenderer renderer = portalClouds.get(index);
        if (renderer == null) {
            renderer = new PortalCloudRenderer();
            portalClouds.set(index, renderer);
        }
        renderer.ensureTexture(resources);
        return renderer;
    }

    void invalidateCloudTextures() {
        for (PortalCloudRenderer renderer : portalClouds) {
            if (renderer != null) {
                renderer.textureLoaded = false;
            }
        }
    }

    private static final class PortalCloudRenderer extends CloudRenderer {
        private boolean textureLoaded;

        void ensureTexture(ResourceManager resources) {
            if (!textureLoaded) {
                apply(prepare(resources, InactiveProfiler.INSTANCE), resources, InactiveProfiler.INSTANCE);
                textureLoaded = true;
            }
        }
    }

    void close() {
        for (PortalCloudRenderer renderer : portalClouds) {
            if (renderer != null) {
                renderer.close();
            }
        }
        portalClouds.clear();
        for (LayerTargets targets : layerTargets) {
            targets.close();
        }
        layerTargets.clear();
        if (maskBuffer != null) {
            maskBuffer.close();
            maskBuffer = null;
        }
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
        if (featureDispatcher != null) {
            featureDispatcher.close();
            featureDispatcher = null;
        }
        if (featureBuffers != null) {
            featureBuffers.close();
            featureBuffers = null;
        }
    }

    private static final class LayerTargets {
        private TextureTarget snapshot;
        private TextureTarget mask;
        private int width;
        private int height;

        void ensure(int width, int height) {
            if (snapshot != null && mask != null && this.width == width && this.height == height) {
                return;
            }
            close();
            this.width = width;
            this.height = height;
            snapshot = new TextureTarget("Crythst portal snapshot", width, height,
                    GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            mask = new TextureTarget("Crythst portal mask", width, height,
                    GpuFormat.RGBA8_UNORM, null);
        }

        void close() {
            if (snapshot != null) {
                snapshot.destroyBuffers();
                snapshot = null;
            }
            if (mask != null) {
                mask.destroyBuffers();
                mask = null;
            }
        }
    }
}
