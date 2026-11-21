package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.integration.portal.PortalTransform;
import io.mikaple.crythst.mixin.client.PortalCameraAccessor;
import io.mikaple.crythst.mixin.client.PortalLevelExtractorAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Captures the real destination scene into one offscreen texture per portal.
 *
 * <h2>When and how</h2>
 *
 * <p>26.3 renders from an extracted {@link LevelRenderState}. Calling
 * {@code LevelRenderer.render} a second time with a different
 * {@link CameraRenderState} is not enough: terrain visibility, entities,
 * block entities, sky and particles were all extracted for the main camera.
 * This class therefore runs at the <b>head</b> of
 * {@code LevelExtractor.extract}, before the main camera's extraction:</p>
 *
 * <ol>
 *     <li>Save the real {@link Camera}'s pose/frustums and the main camera
 *     render state.</li>
 *     <li>Point the real camera at the destination via the link transform and
 *     run a nested {@code LevelExtractor.extract}. The recursion guard makes
 *     that nested extraction skip capture and rebuild every render-state list
 *     for the portal camera.</li>
 *     <li>Rebuild {@code visibleSections} for the portal camera with the
 *     bounded {@link PortalSectionSelection} scan. The nested extraction skips
 *     vanilla's unbounded {@code addSectionsInFrustum} path because the portal
 *     camera has an explicitly captured frustum.</li>
 *     <li>Render directly into the portal's offscreen target by temporarily
 *     overriding {@code GameRenderer.mainRenderTarget()} (see
 *     {@link io.mikaple.crythst.mixin.client.PortalGameRendererMixin}).</li>
 *     <li>Restore the camera, frustums, visible sections and main render state.
 *     The outer {@code extract} call then performs the normal main-camera
 *     extraction, so no second main extraction is needed.</li>
 * </ol>
 *
 * <p>No frame-global render mode is toggled. In particular
 * {@code shouldUseMultiDrawIndirectForTerrain} is left exactly as the
 * extractor sets it; flipping it used to destroy/recreate the chunk-section
 * UBO storage on every capture, flooding the log with "Resizing Chunk
 * Sections..." messages and stuttering the frame. Both passes now use
 * whatever mode the extractor selected.</p>
 *
 * <p>Recursion depth is 1. {@link #capturing} makes nested portal surfaces use
 * the procedural shader, so a portal never samples the texture currently being
 * written.</p>
 */
public final class PortalCapture {
    /** Default ~4.5 captures per second (tunable via crythst.portal.capture.intervalMs). */
    private static final long CAPTURE_INTERVAL_NANOS = readCaptureIntervalNanos();
    /** One portal per round; each capture is a full extraction + world render. */
    private static final int MAX_PORTALS_PER_CAPTURE = 1;
    /** Budget for scheduling compiles of destination sections not yet meshed. */
    private static final int MAX_PORTAL_SECTION_COMPILES = 8;
    /** Default half-resolution offscreen texture (tunable via crythst.portal.capture.scale). */
    private static final float CAPTURE_SCALE = readCaptureScale();
    private static final int MAX_CAPTURE_WIDTH = 1280;
    private static final int MAX_CAPTURE_HEIGHT = 720;
    /** Reject a cached view if the main camera moved/rotated more than this. */
    private static final double MAX_REUSE_CAMERA_DISTANCE_SQUARED = 1.0e-4; // 0.01 block
    private static final float MAX_REUSE_CAMERA_ANGLE_DEGREES = 0.05f;
    private static final long MAX_REUSE_AGE_NANOS = 1_000_000_000L;
    private static final long TIMING_LOG_INTERVAL_NANOS = 1_000_000_000L;
    /** Do not pay for a second scene render for distant portals. */
    private static final double MAX_CAPTURE_DISTANCE = 160.0;
    private static final double MAX_CAPTURE_DISTANCE_SQUARED =
            MAX_CAPTURE_DISTANCE * MAX_CAPTURE_DISTANCE;

    private static final Map<UUID, ViewTarget> TARGETS = new HashMap<>();
    private static final Set<UUID> READY = new HashSet<>();

    /** Diagnostic switch to disable the second scene render. */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("crythst.portal.capture", "true"));
    /** Dump the first captured offscreen target to screenshots/ for A/B diagnosis. */
    private static final boolean DUMP_CAPTURE_TARGET =
            Boolean.getBoolean("crythst.portal.capture.dump");
    private static boolean dumpedCaptureTarget;

    private static CrossFrameResourcePool resourcePool;
    private static FogRenderer fogRenderer;
    private static GlobalSettingsUniform portalGlobalSettings;
    private static ProjectionMatrixBuffer portalProjectionBuffer;
    /** Non-null only while {@code LevelRenderer.render} is drawing a capture pass. */
    private static volatile RenderTarget renderTargetOverride;
    private static long lastCaptureNanos;
    private static long lastTimingLogNanos;
    private static boolean capturing;
    private static boolean registered;
    private static boolean failureLogged;
    private static boolean firstCaptureLogged;
    /** Rate limit for the "invalid portal camera" debug message. */
    private static final long INVALID_CAMERA_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static long lastInvalidCameraLogNanos = -INVALID_CAMERA_LOG_INTERVAL_NANOS;

    private PortalCapture() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // Capture itself is injected at the head of LevelExtractor.extract by
        // PortalCaptureMixin; this lifecycle hook only frees GPU resources.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    }

    /** True while a destination scene is being rendered into an offscreen texture. */
    public static boolean isCapturing() {
        return capturing;
    }

    /** True when a fresh destination texture is available for this portal. */
    public static boolean hasView(UUID portalId) {
        return READY.contains(portalId);
    }

    /** Texture registered for the portal, or {@code null} when no capture exists. */
    public static Identifier textureId(UUID portalId) {
        final ViewTarget target = TARGETS.get(portalId);
        return target == null ? null : target.textureId;
    }

    /**
     * Target that {@code LevelRenderer.render} should use for the duration of
     * the current capture. Read by {@code PortalGameRendererMixin}.
     */
    public static RenderTarget renderTargetOverride() {
        return renderTargetOverride;
    }

    /**
     * Entry point called from {@code PortalCaptureMixin} at the head of
     * {@code LevelExtractor.extract}. Runs before the main camera's state is
     * reset, and the outer extraction fills the main state immediately after
     * this method returns.
     */
    public static void onExtractStart(Camera camera, DeltaTracker deltaTracker, float partialTick) {
        if (capturing) {
            return;
        }
        if (!ENABLED) {
            READY.clear();
            return;
        }
        try {
            captureDestinationViews(camera, deltaTracker, partialTick);
        } catch (Throwable error) {
            logFailure("Crythst portal capture aborted; falling back to procedural surfaces", error);
            READY.clear();
        }
    }

    private static void captureDestinationViews(Camera camera, DeltaTracker deltaTracker,
                                                float partialTick) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final GameRenderer gameRenderer = minecraft.gameRenderer;
        final LevelRenderer renderer = minecraft.levelRenderer;
        final LevelRenderState levelState = gameRenderer.gameRenderState().levelRenderState;

        if (level == null || camera == null) {
            READY.clear();
            pruneTargets(Set.of());
            return;
        }
        // Capture only for the normal gameplay extraction. Secondary cameras
        // (panorama, screenshot tooling, other mods' passes) must not pay for
        // a portal render or disturb the main camera state.
        if (camera != gameRenderer.mainCamera()) {
            return;
        }

        final CameraRenderState mainCamera = levelState.cameraRenderState;
        if (mainCamera == null || mainCamera.pos == null) {
            return;
        }

        // These resets are global and one-shot. A nested extraction would
        // consume them before the main camera's extraction, so skip capture for
        // this single frame and let the normal path perform them.
        final PortalLevelExtractorAccessor extractor =
                (PortalLevelExtractorAccessor) minecraft.levelExtractor;
        if (extractor.crythst$shouldResetLevelRenderData()
                || extractor.crythst$shouldInvalidateCompiledGeometry()
                || extractor.crythst$shouldResetSkyRenderer()
                || extractor.crythst$shouldResetChunkLayerSampler()) {
            return;
        }

        final List<PortalSnapshot> allPortals = PortalClientCache.get();
        final Set<UUID> live = new HashSet<>(Math.max(8, allPortals.size() * 2));
        for (PortalSnapshot portal : allPortals) {
            live.add(portal.id());
        }
        pruneTargets(live);

        if (allPortals.isEmpty()) {
            READY.clear();
            return;
        }

        final long now = System.nanoTime();
        if (now - lastCaptureNanos < CAPTURE_INTERVAL_NANOS) {
            return;
        }

        final List<PortalSnapshot> candidates = new ArrayList<>();
        for (PortalSnapshot portal : allPortals) {
            if (!portal.dimension().equals(level.dimension())) {
                continue;
            }
            if (!portal.viewableIn(level.dimension())) {
                continue;
            }
            if (!isVisible(mainCamera, portal)) {
                continue;
            }
            candidates.add(portal);
        }
        if (candidates.isEmpty()) {
            // Keep the previous textures: the portal quad is frustum-culled
            // independently, and dropping the view here would cause a
            // one-frame procedural flash whenever the player looks away.
            return;
        }
        final Vector3d cameraPosition = new Vector3d(
                mainCamera.pos.x, mainCamera.pos.y, mainCamera.pos.z);
        candidates.sort(Comparator.comparingDouble(portal -> portal.plane().distanceSquared(cameraPosition)));

        final RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null
                || mainTarget.getDepthTexture() == null
                || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }

        ensureResources();
        if (resourcePool == null || fogRenderer == null || portalGlobalSettings == null
                || portalProjectionBuffer == null) {
            return;
        }

        lastCaptureNanos = now;

        final FogData fogData = copyFog(mainCamera.fogData);
        fogRenderer.updateBuffer(fogData);
        final GpuBufferSlice fogSlice = fogRenderer.getBuffer(FogRenderer.FogMode.WORLD);
        final Vector4f fogColor = new Vector4f(fogData.color);

        final LevelRenderer levelRenderer = renderer;
        final List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> savedVisibleSections =
                List.copyOf(levelRenderer.visibleSections());
        final List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> savedNearbySections =
                List.copyOf(levelRenderer.nearbyVisibleSections());
        final Vec3 savedCameraPos = camera.position();
        final GpuBuffer savedGlobalSettings = RenderSystem.getGlobalSettingsUniform();
        final float savedYaw = camera.yRot();
        final float savedPitch = camera.xRot();
        final Frustum savedCullFrustum = camera.getCullFrustum();
        final Frustum savedCapturedFrustum = camera.getCapturedFrustum();

        capturing = true;
        try {
            int count = 0;
            for (PortalSnapshot portal : candidates) {
                if (count >= MAX_PORTALS_PER_CAPTURE) {
                    break;
                }
                boolean rendered = false;
                try {
                    rendered = captureOne(portal, camera, deltaTracker, partialTick,
                            mainCamera, levelState, levelRenderer, mainTarget,
                            fogSlice, fogColor, level);
                } catch (Throwable error) {
                    logFailure("Crythst portal destination capture failed; "
                            + "keeping the previous view (or procedural surface)", error);
                } finally {
                    // Always leave the camera and render state exactly as the
                    // outer main extraction expects them.
                    levelState.cameraRenderState = mainCamera;
                    restoreCamera(camera, savedCameraPos, savedYaw, savedPitch,
                            savedCullFrustum, savedCapturedFrustum);
                    restoreVisibleSections(levelRenderer, savedVisibleSections,
                            savedNearbySections);
                    restoreSectionDispatcherCamera(levelRenderer, mainCamera.pos);
                }
                if (rendered) {
                    count++;
                }
            }
        } finally {
            capturing = false;
            renderTargetOverride = null;
            levelState.cameraRenderState = mainCamera;
            restoreCamera(camera, savedCameraPos, savedYaw, savedPitch,
                    savedCullFrustum, savedCapturedFrustum);
            restoreVisibleSections(levelRenderer, savedVisibleSections,
                    savedNearbySections);
            restoreSectionDispatcherCamera(levelRenderer, mainCamera.pos);
            // Restore the exact global-settings buffer that was active before
            // the capture. GameRenderer.render updates it again for the main
            // pass, but extraction-side readers should not see portal state.
            if (savedGlobalSettings != null) {
                try {
                    RenderSystem.setGlobalSettingsUniform(savedGlobalSettings);
                } catch (Throwable error) {
                    logFailure("Crythst portal global-settings restore failed", error);
                }
            }
            if (fogRenderer != null) {
                fogRenderer.endFrame();
            }
            if (resourcePool != null) {
                resourcePool.endFrame();
            }
        }
    }

    /**
     * Captures one portal. The caller restores the camera/render state in a
     * {@code finally} block even when this returns {@code false} or throws.
     */
    private static boolean captureOne(PortalSnapshot portal, Camera camera,
                                      DeltaTracker deltaTracker, float partialTick,
                                      CameraRenderState mainCamera,
                                      LevelRenderState levelState,
                                      LevelRenderer levelRenderer,
                                      RenderTarget mainTarget,
                                      GpuBufferSlice fogSlice,
                                      Vector4f fogColor,
                                      ClientLevel level) {
        final ViewTarget existingTarget = TARGETS.get(portal.id());
        final long now = System.nanoTime();
        if (existingTarget != null && READY.contains(portal.id())
                && existingTarget.isReusable(mainCamera, now)) {
            return false;
        }

        // One rigid transform serves the camera and both traversal
        // directions. The entry side is mapped to the corresponding exit side,
        // so the view remains continuous and the object always leaves moving
        // away from the destination plane.
        final PortalTransform transform = PortalTransform.between(
                portal.plane(), portal.destinationPlane());
        final Vector3d position = transform.transformPoint(
                new Vector3d(mainCamera.pos.x, mainCamera.pos.y, mainCamera.pos.z));
        if (!PortalCameraSanity.isFinitePosition(position.x, position.y, position.z)) {
            logSkippedInvalidCamera(portal, "non-finite camera position");
            return false;
        }

        final Quaternionf orientation = new Quaternionf(
                mainCamera.orientation == null ? new Quaternionf() : mainCamera.orientation)
                .premul(new Quaternionf(transform.rotation()));
        if (!PortalCameraSanity.isSaneOrientation(orientation)) {
            logSkippedInvalidCamera(portal, "non-finite orientation");
            return false;
        }
        orientation.normalize();
        final Matrix4f viewRotation = new Matrix4f().rotation(
                new Quaternionf(orientation).conjugate());
        final Matrix4f projection = new Matrix4f(mainCamera.projectionMatrix);
        if (!PortalCameraSanity.isUsable(projection, viewRotation, orientation,
                position.x, position.y, position.z)) {
            logSkippedInvalidCamera(portal, "degenerate projection or view rotation");
            return false;
        }

        final Vec3 portalPos = new Vec3(position.x, position.y, position.z);
        final Vector3f forward = orientation.transform(new Vector3f(0.0f, 0.0f, -1.0f));
        final float[] yawPitch = PortalTransform.yawPitch(
                new Vector3d(forward.x, forward.y, forward.z));

        final ViewTarget target = ensureTarget(portal, mainTarget);
        if (target == null) {
            return false;
        }

        // Point the real camera at the destination for the nested extraction.
        // The exact orientation (which may contain roll from a tilted portal)
        // is written into the render state below; Camera only stores yaw/pitch.
        final PortalCameraAccessor access = (PortalCameraAccessor) camera;
        access.crythst$setPosition(portalPos);
        access.crythst$setRotation(yawPitch[0], yawPitch[1]);

        final Frustum frustum = new Frustum(viewRotation, projection);
        frustum.prepare(position.x, position.y, position.z);
        // Marking the frustum as captured makes SectionOcclusionGraph.update
        // skip its own frustum-offset path during the nested extraction. We
        // still populate visibleSections ourselves with a bounded scan.
        access.crythst$setCullFrustum(frustum);
        access.crythst$setCapturedFrustum(frustum);

        final CameraRenderState portalCamera = new CameraRenderState();
        camera.extractRenderState(portalCamera, deltaTracker);
        portalCamera.pos = portalPos;
        portalCamera.blockPos = BlockPos.containing(portalPos);
        portalCamera.initialized = true;
        portalCamera.orientation = orientation;
        portalCamera.viewRotationMatrix = viewRotation;
        portalCamera.cullFrustum = frustum;
        portalCamera.projectionMatrix = projection;
        portalCamera.xRot = yawPitch[1];
        portalCamera.yRot = yawPitch[0];
        portalCamera.fogType = mainCamera.fogType;
        portalCamera.fogData = copyFog(mainCamera.fogData);

        levelState.cameraRenderState = portalCamera;
        final int sectionCount = rebuildVisibleSections(level, levelRenderer, portalCamera);
        scheduleMissingCompiles(level, levelRenderer.visibleSections());
        if (sectionCount <= 0 || levelRenderer.visibleSections().isEmpty()
                || !hasCompiledSection(levelRenderer.visibleSections())) {
            // The destination neighbourhood is not loaded or has no sections
            // in view. Keep any previously captured texture instead of
            // flashing the procedural surface; a never-captured portal simply
            // has no entry in READY.
            logSkippedCapture(portal, "no compiled destination sections in the main ViewArea yet");
            return false;
        }

        // Re-extract terrain visibility, entities, block entities, sky,
        // weather and particles for the portal camera. The recursion guard
        // (capturing == true) prevents this from starting another capture.
        final long extractStart = System.nanoTime();
        Minecraft.getInstance().levelExtractor.extract(deltaTracker, camera, partialTick);
        final long extractEnd = System.nanoTime();

        updateGlobalSettings(Minecraft.getInstance().gameRenderer, levelState, portalPos);

        final net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher =
                levelRenderer.sectionRenderDispatcher();
        if (dispatcher != null) {
            dispatcher.setCameraPosition(portalPos);
        }

        // Isolate every frame-global piece of render state that the nested
        // LevelRenderer.render consumes. At HEAD-of-extract this state belongs
        // to the previous frame's tail (or to GUI extraction), not to us; a
        // stale projection/model-view/scissor would render the destination
        // off-screen or into a tiny corner and leave the portal texture blank.
        final org.joml.Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        final ScissorState savedScissor = new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        final GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        final ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        final GpuBufferSlice savedFog = RenderSystem.getShaderFog();

        modelViewStack.pushMatrix();
        modelViewStack.identity();
        RenderSystem.disableScissorForRenderTypeDraws();
        RenderSystem.setProjectionMatrix(
                portalProjectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
        RenderSystem.setShaderFog(fogSlice);

        // Explicitly initialise the offscreen colour+depth attachments. The
        // capture's own clear pass does this too; doing it here guarantees the
        // target is in a known state even if a later step throws.
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.target.getColorTexture(),
                new Vector4f(fogColor.x, fogColor.y, fogColor.z, 1.0f),
                target.target.getDepthTexture(), 0.0);

        final long renderStart = System.nanoTime();
        try {
            renderTargetOverride = target.target;
            // Sky is left off so the capture does not recreate/rebind the
            // shared SkyRenderer against the offscreen target (SkyRenderer
            // caches the target it was constructed with). Terrain, fog,
            // entities, clouds and block entities still fill the view.
            levelRenderer.render(resourcePool, false, portalCamera,
                    fogSlice, fogColor, false, false);
        } finally {
            renderTargetOverride = null;
            modelViewStack.popMatrix();
            restoreScissor(savedScissor);
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
            RenderSystem.setShaderFog(savedFog);
        }
        final long renderEnd = System.nanoTime();

        READY.add(portal.id());
        target.rememberCamera(mainCamera, System.nanoTime());
        logTimings(portal, sectionCount, target, extractStart, extractEnd, renderStart, renderEnd);
        if (!firstCaptureLogged) {
            firstCaptureLogged = true;
            Crythst.LOGGER.info(
                    "Crythst captured destination view for portal '{}' ({}x{}, {} sections)",
                    portal.name(), target.target.width, target.target.height, sectionCount);
        }
        if (DUMP_CAPTURE_TARGET && !dumpedCaptureTarget) {
            dumpedCaptureTarget = true;
            Screenshot.grab(Minecraft.getInstance().gameDirectory, target.target,
                    message -> Crythst.LOGGER.info(
                            "Crythst portal capture dump: {}", message.getString()));
        }
        return true;
    }

    private static void restoreCamera(Camera camera, Vec3 position, float yaw, float pitch,
                                      Frustum cullFrustum, Frustum capturedFrustum) {
        if (camera == null) {
            return;
        }
        final PortalCameraAccessor access = (PortalCameraAccessor) camera;
        access.crythst$setPosition(position);
        access.crythst$setRotation(yaw, pitch);
        access.crythst$setCullFrustum(cullFrustum);
        access.crythst$setCapturedFrustum(capturedFrustum);
    }

    private static void restoreVisibleSections(LevelRenderer renderer,
                                               List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> visible,
                                               List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> nearby) {
        renderer.clearVisibleSections();
        renderer.visibleSections().addAll(visible);
        renderer.nearbyVisibleSections().addAll(nearby);
    }

    private static void restoreSectionDispatcherCamera(LevelRenderer renderer, Vec3 cameraPos) {
        final net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher =
                renderer.sectionRenderDispatcher();
        if (dispatcher != null && cameraPos != null) {
            dispatcher.setCameraPosition(cameraPos);
        }
    }

    private static void restoreScissor(ScissorState state) {
        if (state != null && state.enabled()) {
            RenderSystem.enableScissorForRenderTypeDraws(
                    state.x(), state.y(), state.width(), state.height());
        } else {
            RenderSystem.disableScissorForRenderTypeDraws();
        }
    }

    /**
     * Rebuilds {@code LevelRenderer.visibleSections} for a portal camera.
     *
     * <p>This intentionally does <em>not</em> call
     * {@code SectionOcclusionGraph.addSectionsInFrustum}: that method's
     * {@code offsetToFullyIncludeCameraCube} loop is unbounded and can hang the
     * render thread for degenerate portal cameras. Instead
     * {@link PortalSectionSelection} performs an explicitly budgeted scan over
     * the loaded section neighbourhood.</p>
     *
     * @return the number of visible sections selected
     */
    private static int rebuildVisibleSections(ClientLevel level, LevelRenderer renderer,
                                              CameraRenderState camera) {
        renderer.clearVisibleSections();
        if (camera == null || camera.cullFrustum == null || camera.pos == null) {
            return 0;
        }
        final ViewArea viewArea = renderer.viewArea();
        if (viewArea == null) {
            return 0;
        }
        // IMPORTANT: do NOT reposition the shared ViewArea to the portal
        // camera. RotatingSectionStorage.setSectionNode() resets the section
        // mesh; bouncing the centre to the portal camera and back every
        // capture uncompiled the destination sections right before the draw
        // (blank texture) and churned the main view's meshes (stutter).
        // Instead, query the portal camera's neighbourhood inside the existing
        // main-camera storage. Sections outside that storage return null and
        // are skipped; a destination beyond the loaded/main area falls back to
        // the procedural surface.
        final SectionPos center = SectionPos.of(camera.blockPos);
        return PortalSectionSelection.collect(level, viewArea, camera.cullFrustum, center,
                renderer.visibleSections(), renderer.nearbyVisibleSections());
    }

    /**
     * Queues compilation for destination sections that have never been meshed.
     *
     * <p>The portal view can look at sections that were outside the main
     * camera's prior visible set (for example terrain behind the player). They
     * are present in the shared ViewArea and loaded, but still {@code
     * UNCOMPILED}, so they contribute no draw calls until something schedules
     * a build. The nested extraction only queues dirty sections; this bounded
     * pass covers the never-meshed remainder. Results become drawable on the
     * next capture round after the normal upload tail.</p>
     */
    private static boolean hasCompiledSection(List<SectionRenderDispatcher.RenderSection> sections) {
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                return true;
            }
        }
        return false;
    }

    private static void scheduleMissingCompiles(ClientLevel level,
                                                List<SectionRenderDispatcher.RenderSection> sections) {
        RenderRegionCache cache = null;
        int budget = MAX_PORTAL_SECTION_COMPILES;
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (budget <= 0) {
                break;
            }
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                continue;
            }
            if (cache == null) {
                cache = new RenderRegionCache();
            }
            section.compileAsync(cache.createRegion(level, section.getSectionNode()));
            budget--;
        }
    }

    private static boolean isVisible(CameraRenderState camera, PortalSnapshot portal) {
        if (camera.pos.distanceToSqr(
                portal.plane().origin().x, portal.plane().origin().y, portal.plane().origin().z)
                > MAX_CAPTURE_DISTANCE_SQUARED) {
            return false;
        }
        if (camera.cullFrustum == null || !camera.initialized) {
            return true;
        }
        final var bounds = portal.plane().bounds();
        return camera.cullFrustum.isVisible(new AABB(
                bounds.min().x, bounds.min().y, bounds.min().z,
                bounds.max().x, bounds.max().y, bounds.max().z));
    }

    /**
     * Debug-only, rate-limited so a permanently broken link cannot flood the
     * log every capture tick.
     */
    private static void logSkippedInvalidCamera(PortalSnapshot portal, String reason) {
        final long now = System.nanoTime();
        if (now - lastInvalidCameraLogNanos < INVALID_CAMERA_LOG_INTERVAL_NANOS) {
            return;
        }
        lastInvalidCameraLogNanos = now;
        Crythst.LOGGER.debug("Crythst skipped portal capture for '{}': {}",
                portal == null ? "<unknown>" : portal.name(), reason);
    }

    private static void logTimings(PortalSnapshot portal, int sectionCount, ViewTarget target,
                                   long extractStart, long extractEnd,
                                   long renderStart, long renderEnd) {
        final long now = System.nanoTime();
        if (now - lastTimingLogNanos < TIMING_LOG_INTERVAL_NANOS) {
            return;
        }
        lastTimingLogNanos = now;
        final double extractMs = (extractEnd - extractStart) / 1.0e6;
        final double renderMs = (renderEnd - renderStart) / 1.0e6;
        Crythst.LOGGER.info(
                "[portal timings] portal='{}' {}x{} sections={} extract={}ms render={}ms "
                        + "total={}ms scale={} interval={}ms",
                portal.name(), target.target.width, target.target.height, sectionCount,
                String.format("%.2f", extractMs),
                String.format("%.2f", renderMs),
                String.format("%.2f", extractMs + renderMs),
                String.format("%.2f", CAPTURE_SCALE),
                CAPTURE_INTERVAL_NANOS / 1_000_000L);
    }

    private static void logSkippedCapture(PortalSnapshot portal, String reason) {
        final long now = System.nanoTime();
        if (now - lastInvalidCameraLogNanos < INVALID_CAMERA_LOG_INTERVAL_NANOS) {
            return;
        }
        lastInvalidCameraLogNanos = now;
        Crythst.LOGGER.info("Crythst portal capture skipped for '{}'; using the previous "
                + "view or procedural fallback: {}",
                portal == null ? "<unknown>" : portal.name(), reason);
    }

    private static void logFailure(String message, Throwable error) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        Crythst.LOGGER.warn(message, error);
    }

    private static long readCaptureIntervalNanos() {
        final String raw = System.getProperty("crythst.portal.capture.intervalMs");
        long millis = 220L;
        if (raw != null) {
            try {
                millis = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        millis = Math.max(50L, Math.min(2000L, millis));
        return millis * 1_000_000L;
    }

    private static float readCaptureScale() {
        final String raw = System.getProperty("crythst.portal.capture.scale");
        double scale = 0.5;
        if (raw != null) {
            try {
                scale = Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        scale = Math.max(0.125, Math.min(1.0, scale));
        return (float) scale;
    }

    private static void ensureResources() {
        if (resourcePool == null) {
            resourcePool = new CrossFrameResourcePool(3);
        }
        if (fogRenderer == null) {
            fogRenderer = new FogRenderer();
        }
        if (portalGlobalSettings == null) {
            portalGlobalSettings = new GlobalSettingsUniform();
        }
        if (portalProjectionBuffer == null) {
            portalProjectionBuffer = new ProjectionMatrixBuffer("crythst portal");
        }
    }

    /**
     * Rewrites the global camera UBO before a capture. The terrain vertex
     * shader reconstructs world positions as
     * {@code Position + (ChunkPosition - CameraBlockPos) + CameraOffset}, so
     * the uniform must describe the portal camera rather than the main camera.
     */
    private static void updateGlobalSettings(GameRenderer gameRenderer,
                                             LevelRenderState levelState, Vec3 cameraPos) {
        final GameRenderState game = gameRenderer.gameRenderState();
        final OptionsRenderState options = game.optionsRenderState;
        final WindowRenderState window = game.windowRenderState;
        portalGlobalSettings.update(
                window.width,
                window.height,
                options.glintStrength,
                levelState.gameTime,
                levelState.worldPartialTicks,
                options.menuBackgroundBlurriness,
                cameraPos,
                options.textureFiltering == TextureFilteringMethod.RGSS);
    }

    private static ViewTarget ensureTarget(PortalSnapshot portal, RenderTarget mainTarget) {
        final int[] size = captureSize(mainTarget);
        ViewTarget existing = TARGETS.get(portal.id());
        if (existing == null) {
            final Identifier textureId = Identifier.fromNamespaceAndPath(
                    Crythst.MOD_ID, "portal_view/" + portal.id());
            final TextureTarget target = createTarget(portal, mainTarget, size[0], size[1]);
            final PortalViewTexture texture = new PortalViewTexture(
                    "crythst_portal_view_" + portal.id(), target,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            existing = new ViewTarget(portal.id(), textureId, texture, target);
            TARGETS.put(portal.id(), existing);
            return existing;
        }

        if (existing.target.width != size[0] || existing.target.height != size[1]) {
            final TextureTarget target = createTarget(portal, mainTarget, size[0], size[1]);
            // The old image belongs to the old size; fall back until the next
            // successful capture has filled the new target.
            READY.remove(portal.id());
            existing.target.destroyBuffers();
            existing.target = target;
            existing.texture.setTarget(target,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
        return existing;
    }

    /**
     * Offscreen capture dimensions: a configurable fraction of the main target,
     * capped so very large windows do not pay a full-resolution second render.
     */
    private static int[] captureSize(RenderTarget mainTarget) {
        int width = Math.max(1, Math.round(mainTarget.width * CAPTURE_SCALE));
        int height = Math.max(1, Math.round(mainTarget.height * CAPTURE_SCALE));
        final double cap = Math.min(
                MAX_CAPTURE_WIDTH / (double) width,
                MAX_CAPTURE_HEIGHT / (double) height);
        if (cap < 1.0) {
            width = Math.max(1, (int) Math.round(width * cap));
            height = Math.max(1, (int) Math.round(height * cap));
        }
        return new int[]{width, height};
    }

    private static TextureTarget createTarget(PortalSnapshot portal, RenderTarget mainTarget,
                                              int width, int height) {
        final GpuFormat colorFormat = mainTarget.getColorTexture().getFormat();
        final GpuFormat depthFormat = mainTarget.getDepthTexture() == null
                ? GpuFormat.D32_FLOAT : mainTarget.getDepthTexture().getFormat();
        // The capture renders straight into this target, so it needs its own
        // depth attachment just like the main target.
        return new TextureTarget(
                "crythst_portal_view_" + portal.id(),
                width, height, colorFormat, depthFormat);
    }

    private static FogData copyFog(FogData source) {
        final FogData copy = new FogData();
        if (source == null) {
            copy.color = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
            copy.environmentalStart = 0.0f;
            copy.environmentalEnd = 256.0f;
            copy.renderDistanceStart = 0.0f;
            copy.renderDistanceEnd = 256.0f;
            copy.skyEnd = 256.0f;
            copy.cloudEnd = 256.0f;
            return copy;
        }
        copy.environmentalStart = source.environmentalStart;
        copy.environmentalEnd = source.environmentalEnd;
        copy.renderDistanceStart = source.renderDistanceStart;
        copy.renderDistanceEnd = source.renderDistanceEnd;
        copy.skyEnd = source.skyEnd;
        copy.cloudEnd = source.cloudEnd;
        copy.color = source.color == null ? new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)
                : new Vector4f(source.color);
        return copy;
    }

    private static void pruneTargets(Set<UUID> live) {
        final Iterator<Map.Entry<UUID, ViewTarget>> iterator = TARGETS.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, ViewTarget> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            Minecraft.getInstance().getTextureManager().release(entry.getValue().textureId);
            entry.getValue().close();
            iterator.remove();
        }
        READY.retainAll(live);
    }

    private static void close() {
        capturing = false;
        renderTargetOverride = null;
        for (ViewTarget target : TARGETS.values()) {
            target.close();
        }
        TARGETS.clear();
        READY.clear();
        if (fogRenderer != null) {
            fogRenderer.close();
            fogRenderer = null;
        }
        if (portalGlobalSettings != null) {
            portalGlobalSettings.close();
            portalGlobalSettings = null;
        }
        if (portalProjectionBuffer != null) {
            portalProjectionBuffer.close();
            portalProjectionBuffer = null;
        }
        if (resourcePool != null) {
            resourcePool.clear();
            resourcePool = null;
        }
    }

    private static final class ViewTarget {
        private final UUID portalId;
        private final Identifier textureId;
        private final PortalViewTexture texture;
        private TextureTarget target;
        private long lastCaptureNanos;
        private Vec3 lastCameraPos;
        private float lastCameraYaw;
        private float lastCameraPitch;

        private ViewTarget(UUID portalId, Identifier textureId,
                           PortalViewTexture texture, TextureTarget target) {
            this.portalId = portalId;
            this.textureId = textureId;
            this.texture = texture;
            this.target = target;
        }

        private boolean isReusable(CameraRenderState camera, long now) {
            if (lastCaptureNanos == 0L || lastCameraPos == null || camera == null
                    || camera.pos == null) {
                return false;
            }
            if (now - lastCaptureNanos > MAX_REUSE_AGE_NANOS) {
                return false;
            }
            if (camera.pos.distanceToSqr(lastCameraPos) > MAX_REUSE_CAMERA_DISTANCE_SQUARED) {
                return false;
            }
            final float yawDelta = Math.abs(Mth.wrapDegrees(camera.yRot - lastCameraYaw));
            final float pitchDelta = Math.abs(camera.xRot - lastCameraPitch);
            return yawDelta <= MAX_REUSE_CAMERA_ANGLE_DEGREES
                    && pitchDelta <= MAX_REUSE_CAMERA_ANGLE_DEGREES;
        }

        private void rememberCamera(CameraRenderState camera, long now) {
            if (camera == null || camera.pos == null) {
                return;
            }
            lastCaptureNanos = now;
            lastCameraPos = camera.pos;
            lastCameraYaw = camera.yRot;
            lastCameraPitch = camera.xRot;
        }

        private void close() {
            if (target != null) {
                target.destroyBuffers();
                target = null;
            }
        }
    }
}
