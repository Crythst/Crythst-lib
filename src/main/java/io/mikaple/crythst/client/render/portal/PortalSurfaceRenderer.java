package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
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
 * Draws portal surfaces with a projective screen-space UV mapping.
 *
 * <p>The captured destination texture is sampled by the portal quad itself, so
 * the quad is both the visible geometry and the clipping mask. Polygons are
 * clipped in homogeneous space against {@code w > epsilon}, which keeps the
 * portal visible when the camera reaches the plane instead of dropping it
 * (the old implementation returned null as soon as one corner crossed).</p>
 */
final class PortalSurfaceRenderer {
    private static final double MAX_RENDER_DISTANCE = 512.0;
    private static final double MAX_RENDER_DISTANCE_SQUARED =
            MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    private static final float CLIP_EPSILON = 1.0e-4f;
    private static final float FALLBACK_RED = 0.48f;
    private static final float FALLBACK_GREEN = 0.16f;
    private static final float FALLBACK_BLUE = 0.86f;
    private static final float FALLBACK_ALPHA = 0.72f;
    private static final boolean FLIP_V = Boolean.getBoolean("crythst.portal.flipV");
    private static volatile boolean viewFailureLogged;

    private PortalSurfaceRenderer() {
    }

    static void render(LevelRenderContext context) {
        if (PortalRenderManager.isCapturing()) {
            // Capture must contain the world behind the portal, never another
            // portal surface (texture, procedural or coloured fallback).
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
        final Matrix4f worldProjection = PortalProjection.worldProjection(
                camera, levelState.playerRenderState,
                context.gameRenderer().gameRenderState().optionsRenderState);
        final Matrix4f viewProjection = new Matrix4f(worldProjection)
                .mul(camera.viewRotationMatrix);

        final Map<RenderType, List<Polygon>> groups = new LinkedHashMap<>();
        final List<Polygon> colored = new ArrayList<>();
        final boolean shaderAvailable = PortalRenderTypes.available();

        for (PortalSnapshot snapshot : snapshots) {
            if (!snapshot.dimension().equals(dimension)) {
                continue;
            }
            final boolean hasCapturedView = !PortalCaptureSettings.FORCE_PROCEDURAL
                    && snapshot.viewableIn(dimension)
                    && PortalRenderManager.hasView(snapshot.id());
            final Matrix4f textureProjection = hasCapturedView
                    ? PortalRenderManager.captureViewProjection(snapshot.id()) : null;
            final Polygon polygon = buildPolygon(
                    snapshot, camera, viewProjection, textureProjection);
            if (polygon == null) {
                continue;
            }
            if (!shaderAvailable) {
                colored.add(polygon);
                continue;
            }
            final RenderType type = chooseType(snapshot, dimension);
            if (type == null) {
                colored.add(polygon);
            } else {
                groups.computeIfAbsent(type, key -> new ArrayList<>()).add(polygon);
            }
        }

        for (Map.Entry<RenderType, List<Polygon>> entry : groups.entrySet()) {
            final List<Polygon> polygons = entry.getValue();
            polygons.sort(Comparator.comparingDouble(Polygon::distanceSquared).reversed());
            try {
                collector.submitCustomGeometry(new PoseStack(), entry.getKey(),
                        (pose, consumer) -> emitShader(polygons, pose, consumer));
            } catch (Throwable error) {
                if (!viewFailureLogged) {
                    viewFailureLogged = true;
                    io.mikaple.crythst.Crythst.LOGGER.warn(
                            "Crythst portal geometry submission failed; using coloured fallback",
                            error);
                }
                PortalRenderTypes.markFailed(error);
                colored.addAll(polygons);
            }
        }

        if (!colored.isEmpty()) {
            colored.sort(Comparator.comparingDouble(Polygon::distanceSquared).reversed());
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(),
                    (pose, consumer) -> emitFallback(colored, pose, consumer));
        }
    }

    private static RenderType chooseType(PortalSnapshot snapshot, ResourceKey<Level> dimension) {
        if (!PortalCaptureSettings.FORCE_PROCEDURAL && snapshot.viewableIn(dimension)
                && PortalRenderManager.hasView(snapshot.id())) {
            final var textureId = PortalRenderManager.textureId(snapshot.id());
            if (textureId != null) {
                try {
                    final RenderType view = PortalRenderTypes.view(textureId);
                    PortalRenderDiagnostics.path(textureId.toString());
                    return view;
                } catch (Throwable error) {
                    if (!viewFailureLogged) {
                        viewFailureLogged = true;
                        io.mikaple.crythst.Crythst.LOGGER.warn(
                                "Crythst portal view render type failed; using procedural surface",
                                error);
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

    private static Polygon buildPolygon(PortalSnapshot snapshot, CameraRenderState camera,
                                        Matrix4f viewProjection, Matrix4f textureProjection) {
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
        final ClipVertex[] vertices = new ClipVertex[4];
        for (int i = 0; i < 4; i++) {
            final Vector3d corner = corners[i];
            final float x = (float) (corner.x - camera.pos.x);
            final float y = (float) (corner.y - camera.pos.y);
            final float z = (float) (corner.z - camera.pos.z);
            final Vector4f clip = viewProjection.transform(new Vector4f(x, y, z, 1.0f));
            final Vector4f textureClip = (textureProjection != null ? textureProjection : viewProjection)
                    .transform(new Vector4f(x, y, z, 1.0f));
            final Vertex vertex = clip.w > CLIP_EPSILON
                    ? vertexFromClip(x, y, z, textureClip)
                    : new Vertex(x, y, z, 0.0f, 0.0f);
            vertices[i] = new ClipVertex(vertex, clip, textureClip);
        }
        final ClipVertex[] ordered = front
                ? vertices
                : new ClipVertex[]{vertices[1], vertices[0], vertices[3], vertices[2]};

        final List<ClipVertex> clipped = clipNearPlane(ordered);
        if (clipped.size() < 3) {
            return null;
        }
        final Vertex[] clippedVertices = new Vertex[clipped.size()];
        for (int i = 0; i < clipped.size(); i++) {
            clippedVertices[i] = clipped.get(i).vertex();
        }
        final Vertex[] original = new Vertex[4];
        for (int i = 0; i < 4; i++) {
            original[i] = ordered[i].vertex();
        }
        return new Polygon(clippedVertices, original, dx * dx + dy * dy + dz * dz);
    }

    private static Vertex vertexFromClip(float x, float y, float z, Vector4f clip) {
        final float w = Math.max(clip.w, CLIP_EPSILON);
        final float u = clip.x / w * 0.5f + 0.5f;
        final float ndcV = clip.y / w * 0.5f + 0.5f;
        final float v = FLIP_V ? 1.0f - ndcV : ndcV;
        return new Vertex(x, y, z, u, v);
    }

    private static List<ClipVertex> clipNearPlane(ClipVertex[] vertices) {
        final List<ClipVertex> output = new ArrayList<>(5);
        for (int i = 0; i < vertices.length; i++) {
            final ClipVertex current = vertices[i];
            final ClipVertex next = vertices[(i + 1) % vertices.length];
            final boolean currentInside = current.clip().w > CLIP_EPSILON;
            final boolean nextInside = next.clip().w > CLIP_EPSILON;
            if (currentInside && nextInside) {
                output.add(next);
            } else if (currentInside) {
                output.add(intersect(current, next));
            } else if (nextInside) {
                output.add(intersect(current, next));
                output.add(next);
            }
        }
        return output;
    }

    private static ClipVertex intersect(ClipVertex from, ClipVertex to) {
        final float denominator = to.clip().w - from.clip().w;
        final float t = denominator == 0.0f
                ? 0.0f : (CLIP_EPSILON - from.clip().w) / denominator;
        final float x = from.vertex().x() + (to.vertex().x() - from.vertex().x()) * t;
        final float y = from.vertex().y() + (to.vertex().y() - from.vertex().y()) * t;
        final float z = from.vertex().z() + (to.vertex().z() - from.vertex().z()) * t;
        final Vector4f clip = new Vector4f(from.clip()).lerp(to.clip(), t);
        final Vector4f textureClip = new Vector4f(from.textureClip()).lerp(to.textureClip(), t);
        clip.w = Math.max(clip.w, CLIP_EPSILON);
        textureClip.w = Math.max(textureClip.w, CLIP_EPSILON);
        return new ClipVertex(vertexFromClip(x, y, z, textureClip), clip, textureClip);
    }

    private static void emitShader(List<Polygon> polygons, PoseStack.Pose pose,
                                   VertexConsumer consumer) {
        for (Polygon polygon : polygons) {
            final Vertex[] vertices = polygon.vertices();
            for (int i = 1; i < vertices.length - 1; i++) {
                addShaderVertex(consumer, pose, vertices[0]);
                addShaderVertex(consumer, pose, vertices[i]);
                addShaderVertex(consumer, pose, vertices[i + 1]);
            }
        }
    }

    private static void addShaderVertex(VertexConsumer consumer, PoseStack.Pose pose, Vertex vertex) {
        consumer.addVertex(pose, vertex.x(), vertex.y(), vertex.z())
                .setUv(vertex.u(), vertex.v())
                .setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void emitFallback(List<Polygon> polygons, PoseStack.Pose pose,
                                     VertexConsumer consumer) {
        for (Polygon polygon : polygons) {
            for (Vertex vertex : polygon.original()) {
                consumer.addVertex(pose, vertex.x(), vertex.y(), vertex.z())
                        .setColor(FALLBACK_RED, FALLBACK_GREEN, FALLBACK_BLUE, FALLBACK_ALPHA);
            }
        }
    }

    private record Vertex(float x, float y, float z, float u, float v) {
    }

    private record ClipVertex(Vertex vertex, Vector4f clip, Vector4f textureClip) {
    }

    private record Polygon(Vertex[] vertices, Vertex[] original, double distanceSquared) {
    }
}
