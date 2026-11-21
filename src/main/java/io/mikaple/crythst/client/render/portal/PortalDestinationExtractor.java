package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.mixin.client.PortalCameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

import java.util.List;

/**
 * Extracts the destination render state for a portal camera.
 *
 * <p>The main {@link LevelRenderState} and the shared {@code ViewArea} are the
 * only 26.3 sources available, so the extractor mutates the real camera and
 * runs the normal {@code LevelExtractor.extract}. A scoped save/restore keeps
 * the outer main-camera extraction intact. Crucially, it never repositions the
 * shared ViewArea: sections are selected from the existing main-camera storage
 * and never-moving storage means no section-mesh resets.</p>
 */
final class PortalDestinationExtractor {

    record Result(CameraRenderState camera, int sectionCount, boolean ready, String failureReason) {
        static Result failure(String reason) {
            return new Result(null, 0, false, reason);
        }
    }

    Scope begin(Minecraft minecraft, Camera camera, CameraRenderState mainCamera,
                LevelRenderState levelState, LevelRenderer levelRenderer,
                PortalViewCamera view) {
        return new Scope(camera, mainCamera, levelState, levelRenderer, view);
    }

    Result extract(Camera camera, DeltaTracker deltaTracker, float partialTick,
                   PortalViewCamera view, CameraRenderState mainCamera,
                   LevelRenderState levelState, LevelRenderer levelRenderer,
                   ClientLevel level) {
        final CameraRenderState portalCamera = new CameraRenderState();
        camera.extractRenderState(portalCamera, deltaTracker);
        portalCamera.pos = view.position();
        portalCamera.blockPos = BlockPos.containing(view.position());
        portalCamera.initialized = true;
        portalCamera.orientation = view.orientation();
        portalCamera.viewRotationMatrix = view.viewRotation();
        portalCamera.cullFrustum = view.frustum();
        portalCamera.projectionMatrix = view.projection();
        portalCamera.xRot = view.pitch();
        portalCamera.yRot = view.yaw();
        portalCamera.fogType = mainCamera.fogType;
        portalCamera.fogData = copyFog(mainCamera.fogData);

        levelState.cameraRenderState = portalCamera;

        levelRenderer.clearVisibleSections();
        final SectionPos center = SectionPos.of(portalCamera.blockPos);
        final int sectionCount = PortalSectionVisibility.collect(
                level, levelRenderer.viewArea(), view.frustum(), center,
                levelRenderer.visibleSections(), levelRenderer.nearbyVisibleSections());
        PortalSectionVisibility.scheduleMissingCompiles(
                level, levelRenderer.visibleSections(),
                PortalCaptureSettings.SECTION_COMPILE_BUDGET);

        if (sectionCount <= 0 || levelRenderer.visibleSections().isEmpty()) {
            return Result.failure("no visible destination sections in the main ViewArea");
        }
        if (!PortalSectionVisibility.hasCompiledSection(levelRenderer.visibleSections())) {
            return Result.failure("destination sections are not compiled yet");
        }

        final SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
        if (dispatcher != null) {
            dispatcher.setCameraPosition(view.position());
        }

        Minecraft.getInstance().levelExtractor.extract(deltaTracker, camera, partialTick);

        // The nested extraction consumes the chunk-cache delta sets. Apply them
        // to the shared occlusion graph now so the main extraction (which will
        // see empty delta sets) does not drop this frame's chunk updates.
        final var deltas = levelState.chunkLoadingRenderState;
        final var occlusionGraph = levelRenderer.sectionOcclusionGraph();
        occlusionGraph.updateLoadedChunks(
                deltas.addedLoadedChunks, deltas.removedLoadedChunks);
        occlusionGraph.updateEmptySections(
                deltas.addedEmptySections, deltas.removedEmptySections);

        return new Result(portalCamera, sectionCount, true, null);
    }

    /**
     * Restores the camera, render state, visibility lists and section-dispatch
     * camera position after the capture render.
     */
    static final class Scope implements AutoCloseable {
        private final Camera camera;
        private final CameraRenderState mainCamera;
        private final LevelRenderState levelState;
        private final LevelRenderer levelRenderer;
        private final Vec3 savedPos;
        private final float savedYaw;
        private final float savedPitch;
        private final net.minecraft.client.renderer.culling.Frustum savedCull;
        private final net.minecraft.client.renderer.culling.Frustum savedCaptured;
        private final List<SectionRenderDispatcher.RenderSection> savedVisible;
        private final List<SectionRenderDispatcher.RenderSection> savedNearby;

        private Scope(Camera camera, CameraRenderState mainCamera,
                      LevelRenderState levelState, LevelRenderer levelRenderer,
                      PortalViewCamera view) {
            this.camera = camera;
            this.mainCamera = mainCamera;
            this.levelState = levelState;
            this.levelRenderer = levelRenderer;
            this.savedPos = camera.position();
            this.savedYaw = camera.yRot();
            this.savedPitch = camera.xRot();
            this.savedCull = camera.getCullFrustum();
            this.savedCaptured = camera.getCapturedFrustum();
            this.savedVisible = List.copyOf(levelRenderer.visibleSections());
            this.savedNearby = List.copyOf(levelRenderer.nearbyVisibleSections());

            final PortalCameraAccessor access = (PortalCameraAccessor) camera;
            access.crythst$setPosition(view.position());
            access.crythst$setRotation(view.yaw(), view.pitch());
            access.crythst$setCullFrustum(view.frustum());
            access.crythst$setCapturedFrustum(view.frustum());
        }

        @Override
        public void close() {
            levelState.cameraRenderState = mainCamera;
            final PortalCameraAccessor access = (PortalCameraAccessor) camera;
            access.crythst$setPosition(savedPos);
            access.crythst$setRotation(savedYaw, savedPitch);
            access.crythst$setCullFrustum(savedCull);
            access.crythst$setCapturedFrustum(savedCaptured);

            levelRenderer.clearVisibleSections();
            levelRenderer.visibleSections().addAll(savedVisible);
            levelRenderer.nearbyVisibleSections().addAll(savedNearby);
            final SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
            if (dispatcher != null && mainCamera != null && mainCamera.pos != null) {
                dispatcher.setCameraPosition(mainCamera.pos);
            }
        }
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
}
