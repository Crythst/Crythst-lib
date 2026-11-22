package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalShape;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure screen-space geometry for the per-pixel portal opening mask.
 *
 * <p>RenderPearl's {@code DepthStencilState} has no stencil operations, so the
 * opening is implemented as an explicit mask attachment: the portal outline is
 * projected to clip space, clipped against the eye plane ({@code w > 0}), then
 * triangulated as a fan. The mask shader only needs triangles in normalized
 * device coordinates, which makes all of the clipping and winding logic
 * testable without allocating a GPU resource.</p>
 */
public final class PortalMaskGeometry {
    private static final float W_EPSILON = 1.0e-4f;
    private static final float INSIDE_EPSILON = 1.0e-6f;

    private PortalMaskGeometry() {
    }

    /** A convex screen-space mask polygon plus its integer pixel bounds. */
    public record Mask(float[] triangles, int minX, int minY, int width, int height) {
        public static final Mask EMPTY = new Mask(new float[0], 0, 0, 0, 0);

        public boolean isEmpty() {
            return triangles.length < 9 || width <= 0 || height <= 0;
        }

        public int vertexCount() {
            return triangles.length / 3;
        }

        public int triangleCount() {
            return triangles.length / 9;
        }
    }

    /**
     * Projects and clips one opening.
     *
     * @param shape             portal opening in world space
     * @param viewProjection    view rotation times projection (camera at origin)
     * @param cameraPosition    world-space camera position
     * @param targetWidth       framebuffer width in pixels
     * @param targetHeight      framebuffer height in pixels
     */
    public static Mask build(PortalShape shape, Matrix4fc viewProjection,
                             Vector3dc cameraPosition, int targetWidth, int targetHeight) {
        if (shape == null || viewProjection == null || cameraPosition == null
                || targetWidth <= 0 || targetHeight <= 0) {
            return Mask.EMPTY;
        }
        final List<Vector3d> outline = shape.worldOutline();
        if (outline.size() < 3) {
            return Mask.EMPTY;
        }
        final List<Vector4f> clipped = new ArrayList<>(outline.size() + 2);
        for (int i = 0; i < outline.size(); i++) {
            final Vector3d current = outline.get(i);
            final Vector3d next = outline.get((i + 1) % outline.size());
            final Vector4f currentClip = project(viewProjection, cameraPosition, current);
            final Vector4f nextClip = project(viewProjection, cameraPosition, next);
            final boolean currentInside = currentClip.w > W_EPSILON;
            final boolean nextInside = nextClip.w > W_EPSILON;
            if (currentInside && nextInside) {
                clipped.add(nextClip);
            } else if (currentInside) {
                clipped.add(intersect(currentClip, nextClip));
            } else if (nextInside) {
                clipped.add(intersect(currentClip, nextClip));
                clipped.add(nextClip);
            }
        }
        if (clipped.size() < 3) {
            return Mask.EMPTY;
        }

        float minNdcX = Float.POSITIVE_INFINITY;
        float minNdcY = Float.POSITIVE_INFINITY;
        float maxNdcX = Float.NEGATIVE_INFINITY;
        float maxNdcY = Float.NEGATIVE_INFINITY;
        final float[] ndc = new float[clipped.size() * 2];
        for (int i = 0; i < clipped.size(); i++) {
            final Vector4f clip = clipped.get(i);
            final float invW = 1.0f / Math.max(clip.w, W_EPSILON);
            final float x = clamp(clip.x * invW, -1.0f, 1.0f);
            final float y = clamp(clip.y * invW, -1.0f, 1.0f);
            ndc[i * 2] = x;
            ndc[i * 2 + 1] = y;
            minNdcX = Math.min(minNdcX, x);
            minNdcY = Math.min(minNdcY, y);
            maxNdcX = Math.max(maxNdcX, x);
            maxNdcY = Math.max(maxNdcY, y);
        }
        if (!(maxNdcX - minNdcX > INSIDE_EPSILON)
                || !(maxNdcY - minNdcY > INSIDE_EPSILON)) {
            return Mask.EMPTY;
        }

        final float[] triangles = new float[(clipped.size() - 2) * 9];
        int cursor = 0;
        for (int i = 1; i < clipped.size() - 1; i++) {
            triangles[cursor++] = ndc[0];
            triangles[cursor++] = ndc[1];
            triangles[cursor++] = 0.0f;
            triangles[cursor++] = ndc[i * 2];
            triangles[cursor++] = ndc[i * 2 + 1];
            triangles[cursor++] = 0.0f;
            triangles[cursor++] = ndc[(i + 1) * 2];
            triangles[cursor++] = ndc[(i + 1) * 2 + 1];
            triangles[cursor++] = 0.0f;
        }

        final int x0 = clampPixel((int) Math.floor((minNdcX * 0.5f + 0.5f) * targetWidth), targetWidth);
        final int x1 = clampPixel((int) Math.ceil((maxNdcX * 0.5f + 0.5f) * targetWidth), targetWidth);
        final int y0 = clampPixel((int) Math.floor((minNdcY * 0.5f + 0.5f) * targetHeight), targetHeight);
        final int y1 = clampPixel((int) Math.ceil((maxNdcY * 0.5f + 0.5f) * targetHeight), targetHeight);
        final int width = Math.max(0, x1 - x0);
        final int height = Math.max(0, y1 - y0);
        if (width <= 0 || height <= 0) {
            return Mask.EMPTY;
        }
        return new Mask(triangles, x0, y0, width, height);
    }

    private static Vector4f project(Matrix4fc viewProjection, Vector3dc cameraPosition,
                                    Vector3dc worldPoint) {
        return viewProjection.transform(new Vector4f(
                (float) (worldPoint.x() - cameraPosition.x()),
                (float) (worldPoint.y() - cameraPosition.y()),
                (float) (worldPoint.z() - cameraPosition.z()),
                1.0f));
    }

    private static Vector4f intersect(Vector4f from, Vector4f to) {
        final float denominator = to.w - from.w;
        final float t = Math.abs(denominator) < 1.0e-9f
                ? 0.0f : (W_EPSILON - from.w) / denominator;
        final Vector4f result = new Vector4f(from).lerp(to, clamp(t, 0.0f, 1.0f));
        result.w = Math.max(result.w, W_EPSILON);
        return result;
    }

    private static int clampPixel(int value, int size) {
        return Math.max(0, Math.min(size, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
