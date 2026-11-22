package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.mixin.client.PortalCameraAccessor;
import io.mikaple.crythst.mixin.client.PortalLevelExtractorAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Extracts a destination {@link LevelRenderState} for one portal camera.
 *
 * <p>The destination state is a separate object. The shared extractor and
 * {@link LevelRenderer} fields are swapped only for the duration of the nested
 * {@code LevelExtractor.extract} call and then restored. The main camera's
 * already-extracted state, the shared {@code ViewArea} centre and the main
 * section meshes are never modified.</p>
 */
final class PortalVisibility {

    record Result(LevelRenderState state, List<SectionRenderDispatcher.RenderSection> sections,
                  int sectionCount, String failureReason) {
        static Result failure(String reason) {
            return new Result(null, List.of(), 0, reason);
        }

        boolean ready() {
            return state != null;
        }
    }

    PortalVisibility() {
    }

    static Result extract(Minecraft minecraft, Camera camera, DeltaTracker deltaTracker,
                          float partialTick, LevelRenderer levelRenderer,
                          PortalViewCamera view, ClientLevel level) {
        if (minecraft == null || camera == null || deltaTracker == null
                || levelRenderer == null || view == null || level == null) {
            return Result.failure("missing extraction inputs");
        }
        final PortalLevelExtractorAccessor extractor =
                (PortalLevelExtractorAccessor) (Object) minecraft.levelExtractor;
        final PortalCameraAccessor cameraAccess = (PortalCameraAccessor) camera;

        final LevelRenderState savedState = extractor.crythst$getLevelRenderState();
        final Vec3 savedPos = camera.position();
        final float savedYaw = camera.yRot();
        final float savedPitch = camera.xRot();
        final Frustum savedCull = camera.getCullFrustum();
        final Frustum savedCaptured = camera.getCapturedFrustum();
        final List<SectionRenderDispatcher.RenderSection> savedVisible =
                List.copyOf(levelRenderer.visibleSections());
        final List<SectionRenderDispatcher.RenderSection> savedNearby =
                List.copyOf(levelRenderer.nearbyVisibleSections());

        try {
            final LevelRenderState destination = new LevelRenderState();
            extractor.crythst$setLevelRenderState(destination);

            cameraAccess.crythst$setPosition(view.position());
            cameraAccess.crythst$setRotation(view.yaw(), view.pitch());
            cameraAccess.crythst$setCullFrustum(view.frustum());
            cameraAccess.crythst$setCapturedFrustum(view.frustum());

            levelRenderer.clearVisibleSections();
            final SectionPos center = SectionPos.of(BlockPos.containing(view.position()));
            final int sections = PortalSectionVisibility.collect(
                    level, levelRenderer.viewArea(), view.frustum(), center,
                    levelRenderer.visibleSections(), levelRenderer.nearbyVisibleSections());
            PortalSectionVisibility.scheduleMissingCompiles(
                    level, levelRenderer.visibleSections(),
                    PortalCaptureSettings.SECTION_COMPILE_BUDGET);
            if (sections <= 0 || levelRenderer.visibleSections().isEmpty()) {
                return Result.failure("no destination sections in the loaded ViewArea");
            }
            if (!PortalSectionVisibility.hasCompiledSection(levelRenderer.visibleSections())) {
                return Result.failure("destination sections are not compiled yet");
            }

            final SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.setCameraPosition(view.position());
            }

            PortalRenderManager.beginDestinationExtraction();
            try {
                // The probe holds the main camera's biome/attribute sample.
                // Re-sample at the destination so sky colour, fog and cloud
                // colour are the destination's, not the entry point's.
                camera.attributeProbe().reset();
                camera.attributeProbe().tick(level, view.position());
                minecraft.levelExtractor.extract(deltaTracker, camera, partialTick);
            } finally {
                PortalRenderManager.endDestinationExtraction();
            }

            final CameraRenderState destinationCamera = destination.cameraRenderState;
            destinationCamera.pos = view.position();
            destinationCamera.blockPos = BlockPos.containing(view.position());
            destinationCamera.initialized = true;
            destinationCamera.orientation = view.orientation();
            destinationCamera.viewRotationMatrix = view.viewRotation();
            destinationCamera.projectionMatrix = view.projection();
            destinationCamera.cullFrustum = view.frustum();
            destinationCamera.xRot = view.pitch();
            destinationCamera.yRot = view.yaw();
            destinationCamera.isFrustumCaptured = true;

            return new Result(destination,
                    List.copyOf(levelRenderer.visibleSections()), sections, null);
        } catch (Throwable error) {
            PortalRenderDiagnostics.failure("Crythst destination extraction failed", error);
            return Result.failure("destination extraction threw "
                    + error.getClass().getSimpleName());
        } finally {
            extractor.crythst$setLevelRenderState(savedState);
            cameraAccess.crythst$setPosition(savedPos);
            cameraAccess.crythst$setRotation(savedYaw, savedPitch);
            cameraAccess.crythst$setCullFrustum(savedCull);
            cameraAccess.crythst$setCapturedFrustum(savedCaptured);
            if (level != null) {
                camera.attributeProbe().reset();
                camera.attributeProbe().tick(level, savedPos);
            }
            levelRenderer.clearVisibleSections();
            levelRenderer.visibleSections().addAll(savedVisible);
            levelRenderer.nearbyVisibleSections().addAll(savedNearby);
            final SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
            if (dispatcher != null) {
                final CameraRenderState main = savedState == null ? null : savedState.cameraRenderState;
                if (main != null && main.pos != null) {
                    dispatcher.setCameraPosition(main.pos);
                }
            }
        }
    }
}
