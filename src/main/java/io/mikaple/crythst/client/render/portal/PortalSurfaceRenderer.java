package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.List;

/**
 * Debug overlay for the portal openings.
 *
 * <p>The actual opening is no longer a textured quad: the destination world is
 * drawn into the main framebuffer by {@link PortalRenderPass}. This renderer is
 * therefore a no-op unless {@code -Dcrythst.portal.debugMask=true} is set, in
 * which case it draws the world-space opening outline. The previous
 * texture-sampling surface is intentionally not part of the default path.</p>
 */
final class PortalSurfaceRenderer {
    private static final double MAX_DEBUG_DISTANCE_SQUARED = 512.0 * 512.0;

    private PortalSurfaceRenderer() {
    }

    static void render(LevelRenderContext context) {
        if (!PortalCaptureSettings.RENDER_ENABLED
                || PortalRenderManager.isDestinationExtracting()
                || !PortalCaptureSettings.DEBUG_MASK
                && !PortalCaptureSettings.OFFSCREEN_FALLBACK) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final List<PortalSnapshot> snapshots = PortalClientCache.get();
        if (snapshots.isEmpty()) {
            return;
        }
        final LevelRenderState levelState = context.levelState();
        final CameraRenderState camera = levelState == null ? null : levelState.cameraRenderState;
        final SubmitNodeCollector collector = context.submitNodeCollector();
        if (camera == null || camera.pos == null || collector == null) {
            return;
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();

        for (PortalSnapshot snapshot : snapshots) {
            if (!snapshot.dimension().equals(dimension) || !snapshot.linked()) {
                continue;
            }
            if (camera.pos.distanceToSqr(snapshot.plane().origin().x,
                    snapshot.plane().origin().y, snapshot.plane().origin().z)
                    > MAX_DEBUG_DISTANCE_SQUARED) {
                continue;
            }
            if (camera.cullFrustum != null && camera.initialized) {
                final var bounds = snapshot.shape().bounds();
                if (!camera.cullFrustum.isVisible(new AABB(
                        bounds.min().x, bounds.min().y, bounds.min().z,
                        bounds.max().x, bounds.max().y, bounds.max().z))) {
                    continue;
                }
            }
            final List<Vector3d> outline = snapshot.shape().worldOutline();
            // Level custom geometry is camera-relative. Passing absolute world
            // coordinates made the debug outline fly far away from the portal.
            final double camX = camera.pos.x;
            final double camY = camera.pos.y;
            final double camZ = camera.pos.z;
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.lines(),
                    (pose, consumer) -> emitOutline(outline, pose, consumer,
                            camX, camY, camZ));
        }
    }

    private static void emitOutline(List<Vector3d> outline, PoseStack.Pose pose,
                                    VertexConsumer consumer,
                                    double camX, double camY, double camZ) {
        for (int i = 0; i < outline.size(); i++) {
            final Vector3d a = outline.get(i);
            final Vector3d b = outline.get((i + 1) % outline.size());
            addVertex(consumer, pose, a, camX, camY, camZ);
            addVertex(consumer, pose, b, camX, camY, camZ);
        }
    }

    private static void addVertex(VertexConsumer consumer, PoseStack.Pose pose,
                                  Vector3d point, double camX, double camY, double camZ) {
        consumer.addVertex(pose,
                        (float) (point.x - camX),
                        (float) (point.y - camY),
                        (float) (point.z - camZ))
                .setColor(0.2f, 1.0f, 0.9f, 1.0f)
                .setNormal(0.0f, 1.0f, 0.0f)
                .setLineWidth(2.0f);
    }
}
