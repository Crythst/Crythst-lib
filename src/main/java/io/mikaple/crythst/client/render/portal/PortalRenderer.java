package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders the animated portal surface from {@code LevelRenderEvents.BEFORE_GIZMOS}.
 *
 * <p>Every portal is submitted as one camera-relative quad with the procedural
 * {@code crythst:core/portal} shader. Only faces turned towards the camera are
 * emitted (the pipeline itself disables hardware culling, so a portal seen from
 * behind is drawn with reversed winding and mirrored UVs rather than
 * disappearing).</p>
 *
 * <p>This is the v1 surface, not a recursive view: it shows an animated star
 * field instead of the space behind the linked portal. Recursive/template
 * rendering is a later TODO item.</p>
 */
public final class PortalRenderer {
    private static final double MAX_RENDER_DISTANCE = 512.0;
    private static final double MAX_RENDER_DISTANCE_SQUARED =
            MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    private static final float FALLBACK_RED = 0.48f;
    private static final float FALLBACK_GREEN = 0.16f;
    private static final float FALLBACK_BLUE = 0.86f;
    private static final float FALLBACK_ALPHA = 0.72f;

    private static volatile boolean registered;

    private PortalRenderer() {
    }

    /**
     * Registers the client-tick snapshot refresh and the level render hook.
     * Call once from the client initializer.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        PortalClientView.register();
        LevelRenderEvents.BEFORE_GIZMOS.register(PortalRenderer::render);
        io.mikaple.crythst.Crythst.LOGGER.info(
                "Crythst portal surface renderer registered (procedural star shader)");
    }

    public static void render(LevelRenderContext context) {
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
        final List<Quad> quads = new ArrayList<>(snapshots.size());
        for (PortalSnapshot snapshot : snapshots) {
            if (!snapshot.dimension().equals(dimension)) {
                continue;
            }
            final Quad quad = buildQuad(snapshot, camera);
            if (quad != null) {
                quads.add(quad);
            }
        }
        if (quads.isEmpty()) {
            return;
        }

        // The pipeline does not write depth, so draw far portals first for a
        // stable painter's order when two surfaces overlap on screen.
        quads.sort(Comparator.comparingDouble(Quad::distanceSquared).reversed());

        if (PortalRenderTypes.available()) {
            try {
                final RenderType type = PortalRenderTypes.portal();
                collector.submitCustomGeometry(new PoseStack(), type,
                        (pose, consumer) -> emitShader(quads, pose, consumer));
                return;
            } catch (Throwable error) {
                PortalRenderTypes.markFailed(error);
            }
        }

        collector.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(),
                (pose, consumer) -> emitFallback(quads, pose, consumer));
    }

    private static Quad buildQuad(PortalSnapshot snapshot, CameraRenderState camera) {
        final var plane = snapshot.plane();
        final Vector3d origin = plane.origin();
        final double dx = camera.pos.x - origin.x;
        final double dy = camera.pos.y - origin.y;
        final double dz = camera.pos.z - origin.z;
        if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQUARED) {
            return null;
        }

        if (camera.cullFrustum != null && camera.initialized) {
            final var bounds = plane.bounds();
            if (!camera.cullFrustum.isVisible(new AABB(
                    bounds.min().x, bounds.min().y, bounds.min().z,
                    bounds.max().x, bounds.max().y, bounds.max().z))) {
                return null;
            }
        }

        final Vector3d normal = plane.normal();
        final boolean front = dx * normal.x + dy * normal.y + dz * normal.z >= 0.0;

        final Vector3d[] corners = plane.corners();
        // Corners are bottom-left, bottom-right, top-right, top-left as seen
        // from the +normal side. Viewing from behind uses reversed winding and
        // mirrored U coordinates.
        final int[] order = front
                ? new int[]{0, 1, 2, 3}
                : new int[]{1, 0, 3, 2};
        final float[][] uv = front
                ? new float[][]{{0.0f, 0.0f}, {1.0f, 0.0f}, {1.0f, 1.0f}, {0.0f, 1.0f}}
                : new float[][]{{1.0f, 0.0f}, {0.0f, 0.0f}, {0.0f, 1.0f}, {1.0f, 1.0f}};

        final Vertex[] vertices = new Vertex[4];
        for (int i = 0; i < 4; i++) {
            final Vector3d corner = corners[order[i]];
            vertices[i] = new Vertex(
                    (float) (corner.x - camera.pos.x),
                    (float) (corner.y - camera.pos.y),
                    (float) (corner.z - camera.pos.z),
                    uv[i][0], uv[i][1]);
        }
        return new Quad(vertices, dx * dx + dy * dy + dz * dz);
    }

    private static void emitShader(List<Quad> quads, PoseStack.Pose pose, VertexConsumer consumer) {
        for (Quad quad : quads) {
            for (Vertex vertex : quad.vertices()) {
                consumer.addVertex(pose, vertex.x(), vertex.y(), vertex.z())
                        .setUv(vertex.u(), vertex.v())
                        .setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }

    private static void emitFallback(List<Quad> quads, PoseStack.Pose pose, VertexConsumer consumer) {
        for (Quad quad : quads) {
            for (Vertex vertex : quad.vertices()) {
                consumer.addVertex(pose, vertex.x(), vertex.y(), vertex.z())
                        .setColor(FALLBACK_RED, FALLBACK_GREEN, FALLBACK_BLUE, FALLBACK_ALPHA);
            }
        }
    }

    private record Vertex(float x, float y, float z, float u, float v) {
    }

    private record Quad(Vertex[] vertices, double distanceSquared) {
    }
}
