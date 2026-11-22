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
 * <p>Per frame:</p>
 * <ol>
 *     <li>select visible linked portals in the current dimension;</li>
 *     <li>ask {@link PortalCaptureScheduler} whether a capture is due (the
 *     default is immediately when the camera or a portal moved);</li>
 *     <li>build the destination camera with {@link PortalViewCamera} (rigid
 *     link transform + oblique exit-plane near clip);</li>
 *     <li>extract destination state in a scoped pass and render it through
 *     {@link PortalRenderPass} into the portal's bounded view target;</li>
 *     <li>{@link PortalSurfaceRenderer} samples that texture on the portal
 *     quad in the main pass.</li>
 * </ol>
 *
 * <p>The shared main {@code ViewArea} is never re-centred; target-camera
 * visibility is selected from the existing storage. Recursive capture is
 * disabled (the surface renderer is skipped while capturing), and at most one
 * destination render is performed per frame.</p>
 */
public final class PortalRenderManager {
    private static final PortalRenderManager INSTANCE = new PortalRenderManager();

    private final PortalViewRegistry registry = new PortalViewRegistry();
    private final PortalDestinationExtractor extractor = new PortalDestinationExtractor();
    private final PortalRenderPass pass = new PortalRenderPass();
    private final PortalCaptureScheduler scheduler = new PortalCaptureScheduler(
            PortalCaptureSettings.BASE_INTERVAL_NANOS,
            PortalCaptureSettings.MAX_INTERVAL_NANOS,
            PortalCaptureSettings.IDLE_REFRESH_NANOS);

    private boolean registered;
    private int captureCursor;

    private PortalRenderManager() {
    }

    public static void register() {
        INSTANCE.registerInternal();
    }

    public static boolean isCapturing() {
        return INSTANCE.pass.capturing();
    }

    public static boolean hasView(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        return target != null && target.ready();
    }

    public static Identifier textureId(UUID portalId) {
        final PortalViewTarget target = INSTANCE.registry.get(portalId);
        return target == null ? null : target.textureId();
    }

    /** True when the registered texture wrapper exposes the target's live colour view. */
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
        return INSTANCE.pass.targetOverride();
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
        if (!PortalCaptureSettings.RENDER_ENABLED || pass.capturing()) {
            return;
        }
        if (!PortalCaptureSettings.CAPTURE_ENABLED || PortalCaptureSettings.FORCE_PROCEDURAL) {
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

        final List<PortalSnapshot> candidates = new ArrayList<>();
        for (PortalSnapshot portal : snapshots) {
            if (!portal.dimension().equals(level.dimension())
                    || !portal.viewableIn(level.dimension())) {
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

        final long now = System.nanoTime();
        boolean poseDirty = false;
        long oldestAge = Long.MAX_VALUE;
        for (PortalSnapshot portal : candidates) {
            final PortalViewTarget target = registry.get(portal.id());
            if (target == null || !target.isReusable(portal, mainCamera, now)) {
                poseDirty = true;
            }
            if (target != null) {
                oldestAge = Math.min(oldestAge, target.ageNanos(now));
            } else {
                oldestAge = Long.MAX_VALUE;
            }
        }
        if (oldestAge == Long.MAX_VALUE) {
            // A portal without any image is always due (handled by poseDirty).
            oldestAge = PortalCaptureSettings.IDLE_REFRESH_NANOS;
        }
        if (!scheduler.due(now, poseDirty, oldestAge)) {
            return;
        }

        final RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null
                || mainTarget.getDepthTexture() == null
                || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }
        final int[] size = PortalCaptureSettings.targetSize(mainTarget);
        scheduler.markAttempt(now);

        final int start = Math.floorMod(captureCursor++, candidates.size());
        pass.beginRound();
        final long roundStart = System.nanoTime();
        double costMs = 0.0;
        int sections = 0;
        try {
            for (int i = 0; i < candidates.size(); i++) {
                final PortalSnapshot portal = candidates.get(start + i < candidates.size()
                        ? start + i : start + i - candidates.size());
                final PortalViewTarget existing = registry.get(portal.id());
                if (existing != null && existing.isReusable(portal, mainCamera, now)) {
                    continue;
                }
                if (existing == null
                        && registry.size() >= PortalCaptureSettings.MAX_ACTIVE_TARGETS) {
                    continue;
                }
                try {
                    final PortalViewTarget target = registry.getOrCreate(
                            portal, mainTarget, size[0], size[1]);
                    final Captured captured = captureOne(minecraft, camera, deltaTracker,
                            partialTick, level, levelRenderer, levelState, mainCamera,
                            gameRenderer, portal, target, now);
                    if (captured != null) {
                        costMs = captured.costMs();
                        sections = captured.sections();
                        break;
                    }
                } catch (Throwable error) {
                    PortalRenderDiagnostics.failure(
                            "Crythst portal view round failed for '" + portal.name() + "'", error);
                }
            }
        } finally {
            pass.endRound();
        }
        scheduler.recordCost(costMs);
        if (PortalCaptureSettings.TRACE) {
            PortalRenderDiagnostics.round(candidates.size(), sections, costMs,
                    (System.nanoTime() - roundStart) / 1.0e6, scheduler.intervalNanos());
        }
    }

    private Captured captureOne(Minecraft minecraft, Camera camera, DeltaTracker deltaTracker,
                                float partialTick, ClientLevel level, LevelRenderer levelRenderer,
                                LevelRenderState levelState, CameraRenderState mainCamera,
                                GameRenderer gameRenderer, PortalSnapshot portal,
                                PortalViewTarget target, long now) {
        final PortalViewCamera.Result cameraResult = PortalViewCamera.build(
                portal, mainCamera, levelState,
                gameRenderer.gameRenderState().optionsRenderState);
        if (cameraResult.camera() == null) {
            PortalRenderDiagnostics.skipped(portal, cameraResult.failureReason());
            return null;
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
                    PortalRenderDiagnostics.skipped(portal, extraction.failureReason());
                    return null;
                }
                final long extractEnd = System.nanoTime();
                final double extractMs = (extractEnd - extractStart) / 1.0e6;
                final double renderMs = pass.render(
                        minecraft, levelRenderer, levelState, mainCamera, view,
                        extraction.camera(), target, camera, level, deltaTracker);
                target.markReady(portal, mainCamera, System.nanoTime(),
                        view.mainViewProjection(), view.mainCapturePosition());
                PortalRenderDiagnostics.firstCapture(portal, target, extraction.sectionCount());
                PortalRenderDiagnostics.dumpIfEnabled(target);
                final double cost = extractMs + renderMs;
                PortalRenderDiagnostics.timings(portal, target, extraction.sectionCount(),
                        extractMs, renderMs, scheduler.intervalNanos());
                return new Captured(cost, extraction.sectionCount());
            }
        } catch (Throwable error) {
            target.markFailed();
            PortalRenderDiagnostics.failure(
                    "Crythst portal destination capture failed; keeping previous view", error);
            return null;
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
        final var bounds = portal.shape().bounds();
        return camera.cullFrustum.isVisible(new AABB(
                bounds.min().x, bounds.min().y, bounds.min().z,
                bounds.max().x, bounds.max().y, bounds.max().z));
    }

    private record Captured(double costMs, int sections) {
    }

    private void close() {
        registry.close();
        pass.close();
    }
}
