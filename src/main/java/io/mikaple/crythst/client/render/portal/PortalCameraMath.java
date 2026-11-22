package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalTransform;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;

/**
 * Pure math for the portal destination camera and projective surface mapping.
 *
 * <p>No Minecraft or render-thread state is touched here, so the exact same
 * formulas can be unit tested. The renderer merely feeds in the current main
 * camera pose/projection and stores the result in a {@link PortalViewTarget}.</p>
 *
 * <p>Identity used by the UV mapping: for a world point {@code q} on the entry
 * plane, the portal camera sees {@code T(q)}. Because the portal camera is the
 * main camera transformed by {@code T}, its view matrix is {@code V * T^-1},
 * hence</p>
 *
 * <pre>
 *     P * V_portal * T(q) = P * V_main * q
 * </pre>
 *
 * <p>The portal quad can therefore use its ordinary main-camera screen position
 * as the texture coordinate, regardless of how the destination plane is
 * oriented. This is the classic projective portal mapping.</p>
 */
public final class PortalCameraMath {
    private PortalCameraMath() {
    }

    /** Camera pose produced by the rigid link transform. */
    public record Pose(Vector3d position, Quaterniond orientation,
                       Matrix4f viewRotation, Matrix4f viewProjection) {
    }

    /**
     * Builds the destination camera pose.
     *
     * @param transform       entry-to-exit rigid transform
     * @param mainPosition    main camera world position
     * @param mainOrientation main camera world orientation
     * @param projection      unmodified main projection (including view bob)
     */
    public static Pose portalCamera(PortalTransform transform, Vector3dc mainPosition,
                                    Quaterniondc mainOrientation, Matrix4fc projection) {
        final Vector3d position = transform.transformPoint(mainPosition);
        final Quaterniond orientation = transform.transformRotation(mainOrientation);
        final Matrix4f viewRotation = new Matrix4f().rotation(
                new Quaternionf(orientation).conjugate());
        final Matrix4f viewProjection = new Matrix4f(projection).mul(viewRotation);
        return new Pose(position, orientation, viewRotation, viewProjection);
    }

    /** Main-camera screen projection used to sample a portal quad. */
    public static Matrix4f mainViewProjection(Matrix4fc projection,
                                              Matrix4fc mainViewRotation) {
        return new Matrix4f(projection).mul(mainViewRotation);
    }

    /**
     * Projects a world point through a (view-rotation only) view projection.
     *
     * @return normalized device coordinates in {@code [-1, 1]} plus the clip
     *         space {@code w}; {@code w <= 0} means the point is behind the eye
     */
    public static Projected project(Matrix4fc viewProjection, Vector3dc cameraPosition,
                                    Vector3dc worldPoint) {
        final Vector4f clip = viewProjection.transform(new Vector4f(
                (float) (worldPoint.x() - cameraPosition.x()),
                (float) (worldPoint.y() - cameraPosition.y()),
                (float) (worldPoint.z() - cameraPosition.z()),
                1.0f));
        return new Projected(clip);
    }

    /** Converts a clip-space point to the clamped texture coordinate region. */
    public static Vector2f textureUv(Projected projected) {
        final float w = projected.w();
        if (Math.abs(w) < 1.0e-7f) {
            return new Vector2f(0.5f, 0.5f);
        }
        return new Vector2f(
                projected.ndcX() * 0.5f + 0.5f,
                projected.ndcY() * 0.5f + 0.5f);
    }

    /**
     * Chooses a capture target size that preserves the main target's aspect
     * ratio exactly enough that normalized UVs and the projection agree.
     */
    public static int[] targetSize(int mainWidth, int mainHeight, float scale,
                                   int maxWidth, int maxHeight) {
        final int safeWidth = Math.max(1, mainWidth);
        final int safeHeight = Math.max(1, mainHeight);
        final double clampedScale = Math.max(0.125, Math.min(1.0, scale));
        int width = Math.max(1, (int) Math.round(safeWidth * clampedScale));
        int height = Math.max(1, (int) Math.round(safeWidth * clampedScale
                * safeHeight / (double) safeWidth));
        // Correct rounding drift by deriving width from height when height is
        // the larger dimension; the resulting aspect stays within one pixel.
        if ((long) width * safeHeight > (long) height * safeWidth) {
            width = Math.max(1, (int) Math.round(height * safeWidth / (double) safeHeight));
        }
        final double cap = Math.min(maxWidth / (double) width, maxHeight / (double) height);
        if (cap < 1.0) {
            width = Math.max(1, (int) Math.round(width * cap));
            height = Math.max(1, (int) Math.round(height * cap));
        }
        return new int[]{width, height};
    }

    /** Clip-space projection of one world point. */
    public record Projected(float x, float y, float z, float w) {
        public Projected(Vector4f clip) {
            this(clip.x, clip.y, clip.z, clip.w);
        }

        public float ndcX() {
            return Math.abs(w) < 1.0e-7f ? 0.0f : x / w;
        }

        public float ndcY() {
            return Math.abs(w) < 1.0e-7f ? 0.0f : y / w;
        }

        public float ndcZ() {
            return Math.abs(w) < 1.0e-7f ? 0.0f : z / w;
        }

        public boolean inFront() {
            return w > 1.0e-5f;
        }
    }
}
