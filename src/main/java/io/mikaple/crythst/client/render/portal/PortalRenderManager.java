package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Portal frame orchestration for the same-frame destination pass.
 *
 * <p>At the end of {@code LevelExtractor.extract}, every visible linked portal
 * gets its own destination {@link LevelRenderState} extracted by
 * {@link PortalVisibility}. At the end of {@code LevelRenderer.addMainPass}, a
 * framegraph pass that reads/writes the <em>main</em> colour+depth handle is
 * added. {@link PortalRenderPass} then rasterizes the destination world into
 * that same framebuffer, masks it to the opening polygon, and composites the
 * previous main pixels back outside the mask.</p>
 *
 * <p>No per-portal colour texture exists on the default path. The only
 * temporary attachments are one mask + one colour/depth snapshot per recursion
 * layer, used to implement the RenderPearl mask equivalent because the public
 * {@code DepthStencilState} exposes no stencil operations.</p>
 */
public final class PortalRenderManager {
    private static final PortalRenderManager INSTANCE = new PortalRenderManager();

    private final PortalVisibility visibility = new PortalVisibility();
    private final PortalRenderPass pass = new PortalRenderPass();
    /** Lazily created on the render thread; dedicated per-frame section storage. */
    private PortalChunkSections chunkSections;

    private volatile boolean registered;
    private volatile boolean destinationExtracting;
    private volatile List<PortalRenderData> layers = List.of();
    private volatile int lastSectionCount;
    private volatile int lastVisiblePortals;
    private long lastExtractStartNanos;

    private PortalRenderManager() {
    }

    public static void register() {
        INSTANCE.registerInternal();
    }

    public static boolean isDestinationExtracting() {
        return INSTANCE.destinationExtracting;
    }

    /** Legacy name used by older portal hooks. */
    public static boolean isCapturing() {
        return INSTANCE.destinationExtracting;
    }

    /**
     * Legacy diagnostics probe. The default path has no per-portal texture, so
     * there is no view target to compare.
     */
    static boolean textureViewMatches(UUID portalId) {
        return false;
    }

    static void beginDestinationExtraction() {
        INSTANCE.destinationExtracting = true;
    }

    static void endDestinationExtraction() {
        INSTANCE.destinationExtracting = false;
    }

    public static void onExtractEnd(Camera camera, DeltaTracker deltaTracker, float partialTick) {
        if (INSTANCE.destinationExtracting || !PortalCaptureSettings.RENDER_ENABLED
                || !PortalCaptureSettings.STENCIL_PATH
                || PortalCaptureSettings.OFFSCREEN_FALLBACK) {
            return;
        }
        try {
            INSTANCE.extractFrame(camera, deltaTracker, partialTick);
        } catch (Throwable error) {
            // Extraction runs inside LevelExtractor.extract. Never let one bad
            // portal definition take down the main frame; drop the whole cache
            // so addFramePass cannot consume a half-built previous frame.
            INSTANCE.layers = List.of();
            PortalRenderDiagnostics.failure(
                    "Crythst portal extraction failed; skipping frame", error);
        }
    }

    /**
     * Called after vanilla's {@code addMainPass} has created the main handle.
     * The portal pass is ordered after it by declaring a read/write dependency
     * on the same resource handle.
     */
    public static ResourceHandle<RenderTarget> addFramePass(FrameGraphBuilder frame,
                                                            ResourceHandle<RenderTarget> main) {
        if (frame == null || main == null || !PortalCaptureSettings.RENDER_ENABLED
                || !PortalCaptureSettings.STENCIL_PATH
                || PortalCaptureSettings.OFFSCREEN_FALLBACK) {
            return main;
        }
        final List<PortalRenderData> data = INSTANCE.layers;
        if (data.isEmpty()) {
            return main;
        }
        return INSTANCE.pass.addPass(frame, main, data);
    }

    private void registerInternal() {
        if (registered) {
            return;
        }
        registered = true;
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_clouds"),
                (ResourceManagerReloadListener) resources -> pass.invalidateCloudTextures());
    }

    private void extractFrame(Camera camera, DeltaTracker deltaTracker, float partialTick) {
        lastExtractStartNanos = System.nanoTime();
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final GameRenderer gameRenderer = minecraft.gameRenderer;
        final LevelRenderer levelRenderer = minecraft.levelRenderer;
        final LevelRenderState mainState = gameRenderer.gameRenderState().levelRenderState;
        if (level == null || camera == null || camera != gameRenderer.mainCamera()
                || mainState == null || mainState.cameraRenderState == null
                || mainState.cameraRenderState.pos == null) {
            layers = List.of();
            return;
        }
        final CameraRenderState mainCamera = mainState.cameraRenderState;
        final List<PortalSnapshot> snapshots = PortalClientCache.effective(lastExtractStartNanos);
        if (snapshots.isEmpty()) {
            layers = List.of();
            lastVisiblePortals = 0;
            lastSectionCount = 0;
            return;
        }

        final RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        if (mainTarget == null || mainTarget.width <= 0 || mainTarget.height <= 0) {
            layers = List.of();
            return;
        }
        final int width = mainTarget.width;
        final int height = mainTarget.height;
        if (chunkSections == null) {
            chunkSections = new PortalChunkSections();
        }
        // One ring rotation per main extraction. Keeping this storage separate
        // from RenderSystem.getDynamicUniforms() avoids fighting the main
        // pass's mapped/instanced Chunk Sections UBO and its resize log spam.
        chunkSections.beginFrame();
        final OptionsRenderState options = gameRenderer.gameRenderState().optionsRenderState;
        final var blockAtlas = minecraft.getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        final int atlasWidth = blockAtlas.getWidth(0);
        final int atlasHeight = blockAtlas.getHeight(0);
        final Vector3d cameraPosition = new Vector3d(
                mainCamera.pos.x, mainCamera.pos.y, mainCamera.pos.z);

        final List<PortalSnapshot> roots = new ArrayList<>();
        for (PortalSnapshot portal : snapshots) {
            if (!portal.dimension().equals(level.dimension())
                    || !portal.viewableIn(level.dimension())
                    || !isVisible(mainCamera, portal)) {
                continue;
            }
            roots.add(portal);
        }
        roots.sort(Comparator.comparingDouble(
                portal -> portal.plane().distanceSquared(cameraPosition)));

        final List<PortalRenderData> extracted = new ArrayList<>();
        final Set<UUID> rendered = new HashSet<>();
        int sectionCount = 0;
        int rootsRendered = 0;
        for (PortalSnapshot portal : roots) {
            if (rootsRendered >= PortalCaptureSettings.MAX_PORTALS_PER_ROUND) {
                break;
            }
            final PortalViewCamera.Result cameraResult = PortalViewCamera.build(
                    portal, mainCamera, mainState,
                    gameRenderer.gameRenderState().optionsRenderState);
            if (cameraResult.camera() == null) {
                PortalRenderDiagnostics.skipped(portal, cameraResult.failureReason());
                continue;
            }
            final PortalViewCamera view = cameraResult.camera();
            final PortalVisibility.Result extractedState = visibility.extract(
                    minecraft, camera, deltaTracker, partialTick, levelRenderer, view, level);
            if (!extractedState.ready()) {
                PortalRenderDiagnostics.skipped(portal, extractedState.failureReason());
                continue;
            }

            final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                    portal.shape(), view.mainViewProjection(),
                    new Vector3d(mainCamera.pos.x, mainCamera.pos.y, mainCamera.pos.z),
                    width, height);
            if (mask.isEmpty()) {
                continue;
            }
            final ChunkSectionsToRender chunkRender = chunkSections.prepare(
                    levelRenderer.sectionRenderDispatcher(), view.viewRotation(),
                    extractedState.sections(), atlasWidth, atlasHeight, options);
            extracted.add(new PortalRenderData(portal, view, extractedState.state(), extractedState.hitboxes(),
                    extractedState.sections(), new Matrix4f(view.mainViewProjection()),
                    mainCamera.pos, mask, chunkRender, 1, 0,
                    extractedState.sectionCount()));
            rendered.add(portal.id());
            sectionCount += extractedState.sectionCount();
            rootsRendered++;

            if (PortalCaptureSettings.MAX_RECURSION <= 1) {
                continue;
            }
            final PortalRenderData child = extractChild(
                    minecraft, camera, deltaTracker, partialTick, level, levelRenderer,
                    gameRenderer, portal, view, extractedState, rendered, width, height,
                    atlasWidth, atlasHeight, options);
            if (child != null) {
                extracted.add(child);
                sectionCount += child.sectionCount();
                rendered.add(child.portal().id());
            }
        }

        if (!extracted.isEmpty()) {
            // Grow the shared sequential index buffer during extraction, before
            // any pass is recorded, instead of mid-framegraph.
            RenderSystem.resizeAllAutoStorageIndexBuffers();
        }
        layers = List.copyOf(extracted);
        lastVisiblePortals = rootsRendered;
        lastSectionCount = sectionCount;
        if (PortalCaptureSettings.TRACE) {
            PortalRenderDiagnostics.round(lastVisiblePortals, lastSectionCount, 0.0,
                    (System.nanoTime() - lastExtractStartNanos) / 1.0e6, 0L);
        }
    }

    private PortalRenderData extractChild(Minecraft minecraft, Camera camera,
                                          DeltaTracker deltaTracker, float partialTick,
                                          ClientLevel level, LevelRenderer levelRenderer,
                                          GameRenderer gameRenderer, PortalSnapshot parent,
                                          PortalViewCamera parentView,
                                          PortalVisibility.Result parentState,
                                          Set<UUID> rendered, int width, int height,
                                          int atlasWidth, int atlasHeight,
                                          OptionsRenderState options) {
        final CameraRenderState parentCamera = parentState.state().cameraRenderState;
        final List<PortalSnapshot> candidates = new ArrayList<>();
        final Vector3d parentPosition = new Vector3d(
                parentCamera.pos.x, parentCamera.pos.y, parentCamera.pos.z);
        final Set<UUID> ancestors = Set.of(parent.id());
        for (PortalSnapshot portal : PortalClientCache.effective(lastExtractStartNanos)) {
            if (rendered.contains(portal.id())
                    || !portal.dimension().equals(level.dimension())
                    || !portal.viewableIn(level.dimension())) {
                continue;
            }
            if (PortalRecursion.nextLayer(1, PortalCaptureSettings.MAX_RECURSION,
                    portal.id(), ancestors, parent.linkedPortalId()) == 0) {
                continue;
            }
            if (!isVisible(parentCamera, portal)) {
                continue;
            }
            candidates.add(portal);
        }
        candidates.sort(Comparator.comparingDouble(
                portal -> portal.plane().distanceSquared(parentPosition)));
        for (PortalSnapshot child : candidates) {
            final PortalViewCamera.Result cameraResult = PortalViewCamera.build(
                    child, parentCamera, parentState.state(),
                    gameRenderer.gameRenderState().optionsRenderState,
                    parentView.projection());
            if (cameraResult.camera() == null) {
                continue;
            }
            final PortalViewCamera childView = cameraResult.camera();
            final PortalVisibility.Result childState = visibility.extract(
                    minecraft, camera, deltaTracker, partialTick, levelRenderer,
                    childView, level);
            if (!childState.ready()) {
                continue;
            }
            final Matrix4f screenViewProjection = new Matrix4f(parentView.projection())
                    .mul(parentView.viewRotation());
            final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                    child.shape(), screenViewProjection,
                    new Vector3d(parentCamera.pos.x, parentCamera.pos.y, parentCamera.pos.z),
                    width, height);
            if (mask.isEmpty()) {
                continue;
            }
            final ChunkSectionsToRender chunkRender = chunkSections.prepare(
                    levelRenderer.sectionRenderDispatcher(), childView.viewRotation(),
                    childState.sections(), atlasWidth, atlasHeight, options);
            return new PortalRenderData(child, childView, childState.state(), childState.hitboxes(),
                    childState.sections(), screenViewProjection, parentCamera.pos,
                    mask, chunkRender, 2, 1, childState.sectionCount());
        }
        return null;
    }

    private static boolean isVisible(CameraRenderState camera, PortalSnapshot portal) {
        if (camera == null || camera.pos == null || portal.plane() == null) {
            return false;
        }
        if (camera.pos.distanceToSqr(
                portal.plane().origin().x, portal.plane().origin().y,
                portal.plane().origin().z)
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

    private void close() {
        layers = List.of();
        pass.close();
        if (chunkSections != null) {
            chunkSections.close();
            chunkSections = null;
        }
    }
}
