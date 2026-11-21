package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.mixin.client.PortalLevelExtractorAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point and orchestrator for the portal view pipeline.
 *
 * <p>Responsibilities are split into focused collaborators:</p>
 * <ul>
 *     <li>{@link PortalViewRegistry} / {@link PortalViewTarget} — per-portal
 *     target, texture registration, lifecycle and freshness;</li>
 *     <li>{@link PortalViewCamera} — link transform, projection and frustum;</li>
 *     <li>{@link PortalDestinationExtractor} — scoped destination extraction,
 *     bounded section selection and state restore;</li>
 *     <li>{@link PortalOffscreenPass} — one isolated RenderPearl world render
 *     into the target;</li>
 *     <li>{@link PortalSurfaceRenderer} — quad UVs, near clipping and
 *     composite;</li>
 *     <li>{@link PortalCaptureTimings} — cadence/adaptive budget.</li>
 * </ul>
 */
public final class PortalRenderManager {
    private static final PortalRenderManager INSTANCE = new PortalRenderManager();

    private final PortalViewRegistry registry = new PortalViewRegistry();
    private final PortalDestinationExtractor extractor = new PortalDestinationExtractor();
    private final PortalOffscreenPass offscreen = new PortalOffscreenPass();
    private final PortalCaptureTimings timings = new PortalCaptureTimings();

    private boolean registered;

    private PortalRenderManager() {
    }

    public static void register() {
        INSTANCE.registerInternal();
    }

    public static boolean isCapturing() {
        return INSTANCE.offscreen.capturing();
    }

    public static boolean hasView(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        return target != null && target.ready();
    }

    public static Identifier textureId(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        return target == null ? null : target.textureId();
    }

    /**
     * View-projection captured with the texture. The surface renderer uses it
     * for UVs so a static captured image stays fixed to the portal plane
     * instead of sliding with the current camera between refreshes.
     */
    /** True when the registered texture wrapper currently exposes the target's live colour view. */
    static boolean textureViewMatches(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        if (target == null || target.target() == null || target.target().getColorTextureView() == null) {
            return false;
        }
        try {
            final var texture = Minecraft.getInstance().getTextureManager()
                    .getTexture(target.textureId());
            return texture.getTextureView() == target.target().getColorTextureView();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static org.joml.Matrix4f captureViewProjection(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        return target == null || !target.ready() ? null : target.captureViewProjection();
    }

    static Vec3 captureCameraPos(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        return target == null || !target.ready() ? null : target.captureCameraPos();
    }

    public static RenderTarget renderTargetOverride() {
        return INSTANCE.offscreen.targetOverride();
    }

    public static void onExtractStart(Camera camera, DeltaTracker deltaTracker, float partialTick) {
        INSTANCE.capture(camera, deltaTracker, partialTick);
    }

    private void registerInternal() {
        if (registered) {
            return;
        }
        registered = true;
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    }

    private void capture(Camera camera, DeltaTracker deltaTracker, float partialTick) {
        if (offscreen.capturing()) {
            return;
        }
        if (!PortalCaptureSettings.ENABLED || PortalCaptureSettings.FORCE_PROCEDURAL) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final GameRenderer gameRenderer = minecraft.gameRenderer;
        final LevelRenderer levelRenderer = minecraft.levelRenderer;
        final LevelRenderState levelState = gameRenderer.gameRenderState().levelRenderState;
        if (level == null || camera == null || camera != gameRenderer.mainCamera()) {
            return;
        }

        final CameraRenderState mainCamera = levelState.cameraRenderState;
        if (mainCamera == null || mainCamera.pos == null) {
            return;
        }

        final PortalLevelExtractorAccessor extractorAccess =
                (PortalLevelExtractorAccessor) minecraft.levelExtractor;
        if (extractorAccess.crythst$shouldResetLevelRenderData()
                || extractorAccess.crythst$shouldInvalidateCompiledGeometry()
                || extractorAccess.crythst$shouldResetSkyRenderer()
                || extractorAccess.crythst$shouldResetChunkLayerSampler()) {
            return;
        }

        final List<PortalSnapshot> snapshots = PortalClientCache.get();
        final Set<UUID> live = new HashSet<>(Math.max(8, snapshots.size() * 2));
        for (PortalSnapshot portal : snapshots) {
            live.add(portal.id());
        }
        registry.prune(live);
        if (snapshots.isEmpty()) {
            return;
        }

        final long now = System.nanoTime();
        if (!timings.due(now)) {
            return;
        }

        final List<PortalSnapshot> candidates = new ArrayList<>();
        for (PortalSnapshot portal : snapshots) {
            if (!portal.dimension().equals(level.dimension()) || !portal.viewableIn(level.dimension())) {
                continue;
            }
            if (!isVisible(mainCamera, portal)) {
                continue;
            }
            candidates.add(portal);
        }
        if (candidates.isEmpty()) {
            return;
        }
        final Vector3d cameraPosition = new Vector3d(
                mainCamera.pos.x, mainCamera.pos.y, mainCamera.pos.z);
        candidates.sort(Comparator.comparingDouble(
                portal -> portal.plane().distanceSquared(cameraPosition)));

        final RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null
                || mainTarget.getDepthTexture() == null
                || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }
        final int[] size = PortalCaptureSettings.targetSize(mainTarget);
        timings.markAttempt(now);

        offscreen.beginRound();
        try {
            int captured = 0;
            for (PortalSnapshot portal : candidates) {
                if (captured >= PortalCaptureSettings.MAX_PORTALS_PER_ROUND) {
                    break;
                }
                try {
                    final PortalViewTarget target = registry.getOrCreate(
                            portal, mainTarget, size[0], size[1]);
                    if (target.isReusable(mainCamera, now)) {
                        continue;
                    }
                    if (captureOne(minecraft, camera, deltaTracker, partialTick, level,
                            levelRenderer, levelState, mainCamera, gameRenderer,
                            portal, target, now)) {
                        captured++;
                    }
                } catch (Throwable error) {
                    PortalRenderDiagnostics.failure(
                            "Crythst portal view round failed for '" + portal.name() + "'", error);
                }
            }
        } finally {
            offscreen.endRound();
        }
    }

    private boolean captureOne(Minecraft minecraft, Camera camera, DeltaTracker deltaTracker,
                               float partialTick, ClientLevel level, LevelRenderer levelRenderer,
                               LevelRenderState levelState, CameraRenderState mainCamera,
                               GameRenderer gameRenderer, PortalSnapshot portal,
                               PortalViewTarget target, long now) {
        final PortalViewCamera.Result cameraResult = PortalViewCamera.build(
                portal, mainCamera, levelState,
                gameRenderer.gameRenderState().optionsRenderState);
        if (cameraResult.camera() == null) {
            // Keep any previous image; a transient bad frame must not flash
            // the procedural surface.
            PortalRenderDiagnostics.skipped(portal, cameraResult.failureReason());
            return false;
        }
        final PortalViewCamera view = cameraResult.camera();

        try {
            final long extractStart = System.nanoTime();
            try (PortalDestinationExtractor.Scope scope = extractor.begin(
                    minecraft, camera, mainCamera, levelState, levelRenderer, view)) {
                final PortalDestinationExtractor.Result extraction = extractor.extract(
                        camera, deltaTracker, partialTick, view, mainCamera,
                        levelState, levelRenderer, level);
                if (!extraction.ready()) {
                    // The target was not touched in this round; keep the last
                    // valid image so the portal stays stable.
                    PortalRenderDiagnostics.skipped(portal, extraction.failureReason());
                    return false;
                }
                final long extractEnd = System.nanoTime();
                final double extractMs = (extractEnd - extractStart) / 1.0e6;
                final double renderMs = offscreen.render(
                        minecraft, levelRenderer, levelState, mainCamera, view,
                        extraction.camera(), target);
                target.markReady(mainCamera, System.nanoTime(), view.mainViewProjection(),
                        view.mainCapturePosition());
                PortalRenderDiagnostics.firstCapture(portal, target, extraction.sectionCount());
                PortalRenderDiagnostics.dumpIfEnabled(target);
                timings.record(portal, target, extraction.sectionCount(),
                        extractMs, renderMs, System.nanoTime());
                return true;
            }
        } catch (Throwable error) {
            target.markFailed();
            PortalRenderDiagnostics.failure(
                    "Crythst portal destination capture failed; keeping previous view", error);
            return false;
        }
    }

    private static boolean isVisible(CameraRenderState camera, PortalSnapshot portal) {
        if (camera.pos.distanceToSqr(
                portal.plane().origin().x, portal.plane().origin().y, portal.plane().origin().z)
                > PortalCaptureSettings.MAX_CAPTURE_DISTANCE_SQUARED) {
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

    private void close() {
        registry.close();
        offscreen.close();
    }
}
