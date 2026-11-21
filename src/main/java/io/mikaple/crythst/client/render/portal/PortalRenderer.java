package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the portal surface from {@code LevelRenderEvents.BEFORE_GIZMOS}.
 *
 * <p>For a portal with a fresh destination capture, the quad samples the
 * offscreen texture using the portal's screen-space UVs. The destination view
 * was rendered from the main camera transformed by the portal link, with the
 * same projection, so the image lines up exactly with the portal opening.</p>
 *
 * <p>When no capture is available (first frames, throttling, cross-dimension,
 * invisible destination, shader failure) the same quad falls back to the
 * procedural animated surface. Nested portals inside a capture pass are drawn
 * with the procedural shader too, which keeps the recursion depth at one and
 * prevents texture feedback.</p>
 */
public final class PortalRenderer {
    private static final double MAX_RENDER_DISTANCE = 512.0;
    private static final double MAX_RENDER_DISTANCE_SQUARED =
            MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    private static final float FALLBACK_RED = 0.48f;
    private static final float FALLBACK_GREEN = 0.16f;
    private static final float FALLBACK_BLUE = 0.86f;
    private static final float FALLBACK_ALPHA = 0.72f;

    /**
     * Diagnostic switch for target-vs-screen texture orientation. The default
     * matches vanilla target-to-target blits; set
     * {@code -Dcrythst.portal.flipV=true} if a particular backend presents the
     * copy upside down.
     */
    private static final boolean FLIP_V = Boolean.getBoolean("crythst.portal.flipV");

    /**
     * A/B switch: draw the procedural surface even when a captured destination
     * texture is available.
     */
    private static final boolean FORCE_PROCEDURAL =
            Boolean.getBoolean("crythst.portal.forceProcedural")
                    || "false".equalsIgnoreCase(
                    System.getProperty("crythst.portal.capture", "true"));

    private static volatile boolean registered;
    private static volatile boolean viewFailureLogged;
    private static volatile boolean viewPathLogged;

    private PortalRenderer() {
    }

    /**
     * Registers the client-tick snapshot refresh, the destination capture hook
     * and the level render hook. Call once from the client initializer.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        PortalClientView.register();
        PortalCapture.register();
        PortalTeleportFade.register();
        LevelRenderEvents.BEFORE_GIZMOS.register(PortalRenderer::render);
        Crythst.LOGGER.info(
                "Crythst portal renderer registered (destination capture + procedural fallback)");
        if (FORCE_PROCEDURAL) {
            Crythst.LOGGER.info(
                    "Crythst portal debug: procedural surface forced (capture A/B mode)");
        }
    }

    public static void render(LevelRenderContext context) {
        // The destination capture must contain the world behind the portal,
        // never another portal surface. Skipping every portal submission here
        // (texture, procedural and coloured fallback) also guarantees no
        // recursive texture feedback and removes the old "stars inside the
        // portal view" artifact. The main pass is unaffected; uncaptured
        // portals may still draw the procedural surface there.
        if (PortalCapture.isCapturing()) {
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
        final Matrix4f viewProjection = new Matrix4f(camera.projectionMatrix)
                .mul(camera.viewRotationMatrix);

        final Map<RenderType, List<Quad>> groups = new LinkedHashMap<>();
        final List<Quad> coloredQuads = new ArrayList<>();
        final boolean shaderAvailable = PortalRenderTypes.available();
        final boolean capturing = PortalCapture.isCapturing();

        for (PortalSnapshot snapshot : snapshots) {
            if (!snapshot.dimension().equals(dimension)) {
                continue;
            }
            final Quad quad = buildQuad(snapshot, camera, viewProjection);
            if (quad == null) {
                continue;
            }

            if (!shaderAvailable) {
                coloredQuads.add(quad);
                continue;
            }

            final RenderType type = chooseType(snapshot, capturing, dimension);
            if (type == null) {
                coloredQuads.add(quad);
            } else {
                groups.computeIfAbsent(type, key -> new ArrayList<>()).add(quad);
            }
        }

        for (Map.Entry<RenderType, List<Quad>> entry : groups.entrySet()) {
            final List<Quad> quads = entry.getValue();
            quads.sort(Comparator.comparingDouble(Quad::distanceSquared).reversed());
            final RenderType type = entry.getKey();
            try {
                collector.submitCustomGeometry(new PoseStack(), type,
                        (pose, consumer) -> emitShader(quads, pose, consumer));
            } catch (Throwable error) {
                if (!viewFailureLogged) {
                    viewFailureLogged = true;
                    Crythst.LOGGER.warn("Crythst portal geometry submission failed; "
                            + "using coloured fallback", error);
                }
                PortalRenderTypes.markFailed(error);
                coloredQuads.addAll(quads);
            }
        }

        if (!coloredQuads.isEmpty()) {
            coloredQuads.sort(Comparator.comparingDouble(Quad::distanceSquared).reversed());
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(),
                    (pose, consumer) -> emitFallback(coloredQuads, pose, consumer));
        }
    }

    /**
     * Picks the captured-view render type when a texture is ready, otherwise
     * the procedural surface.
     */
    private static RenderType chooseType(PortalSnapshot snapshot, boolean capturing,
                                          ResourceKey<Level> dimension) {
        // A captured view is only valid while the link still targets a plane
        // in the dimension currently being rendered. Cross-dimension links
        // (and stale snapshots after a relink) fall back to the procedural
        // surface instead of showing an old, wrong scene.
        if (!FORCE_PROCEDURAL && !capturing && snapshot.viewableIn(dimension)
                && PortalCapture.hasView(snapshot.id())) {
            final Identifier textureId = PortalCapture.textureId(snapshot.id());
            if (textureId != null) {
                try {
                    final RenderType viewType = PortalRenderTypes.view(textureId);
                    if (!viewPathLogged) {
                        viewPathLogged = true;
                        Crythst.LOGGER.info(
                                "Crythst portal render path: offscreen destination texture '{}'", textureId);
                    }
                    return viewType;
                } catch (Throwable error) {
                    if (!viewFailureLogged) {
                        viewFailureLogged = true;
                        Crythst.LOGGER.warn("Crythst portal view render type failed; "
                                + "falling back to the procedural surface", error);
                    }
                }
            }
        }
        try {
            return PortalRenderTypes.procedural();
        } catch (Throwable error) {
            PortalRenderTypes.markFailed(error);
            return null;
        }
    }

    private static Quad buildQuad(PortalSnapshot snapshot, CameraRenderState camera,
                                  Matrix4f viewProjection) {
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
        final Vertex[] vertices = new Vertex[4];
        for (int i = 0; i < 4; i++) {
            final Vector3d corner = corners[i];
            final float x = (float) (corner.x - camera.pos.x);
            final float y = (float) (corner.y - camera.pos.y);
            final float z = (float) (corner.z - camera.pos.z);

            // Screen-space UV for the projective portal mapping. This is the
            // same NDC -> texture convention used by vanilla screenquad.vsh
            // and target-to-target blits (NDC y = -1 is v = 0).
            final Vector4f clip = viewProjection.transform(new Vector4f(x, y, z, 1.0f));
            if (clip.w <= 1.0e-4f) {
                return null;
            }
            final float u = clip.x / clip.w * 0.5f + 0.5f;
            final float ndcV = clip.y / clip.w * 0.5f + 0.5f;
            final float v = FLIP_V ? 1.0f - ndcV : ndcV;
            vertices[i] = new Vertex(x, y, z, u, v);
        }

        // Reverse winding for the back side; per-corner screen UVs stay intact.
        final Vertex[] ordered = front
                ? vertices
                : new Vertex[]{vertices[1], vertices[0], vertices[3], vertices[2]};
        return new Quad(ordered, dx * dx + dy * dy + dz * dz);
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
