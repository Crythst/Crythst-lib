package io.mikaple.crythst.client.render.portal;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Oblique near-plane clipping for the destination render.
 *
 * <p>Vanilla/RenderPearl has no user clip plane, but a perspective projection
 * can be modified (Lengyel) so that the near plane becomes an arbitrary plane
 * in view space. The portal exit plane is the natural near plane: everything
 * between the transformed camera and the exit is behind the portal and must not
 * leak into the view. The x/y terms and therefore the screen-space UV mapping
 * are unchanged; only the depth row is replaced.</p>
 *
 * <p>Minecraft 26.3 uses a reverse-Z, zero-to-one projection
 * ({@code near -> 1}, {@code far -> 0}). The other conventions are provided so
 * the math remains testable and future-proof.</p>
 */
public final class PortalClip {
    private static final double EPSILON = 1.0e-9;

    public enum DepthConvention {
        /** OpenGL default: {@code near -> -1}, {@code far -> 1}. */
        NEGATIVE_ONE_TO_ONE,
        /** D3D/Vulkan default: {@code near -> 0}, {@code far -> 1}. */
        ZERO_TO_ONE,
        /** Minecraft 26.3 / RenderPearl: {@code near -> 1}, {@code far -> 0}. */
        ZERO_TO_ONE_REVERSED
    }

    private PortalClip() {
    }

    /**
     * Returns a copy of {@code projection} whose near plane is the portal plane.
     *
     * <p>The plane normal is flipped automatically so the plane is oriented
     * toward the camera. The kept half-space is the side away from the camera,
     * i.e. the destination world seen through the opening.</p>
     */
    public static Matrix4f obliqueNearPlane(Matrix4fc projection,
                                            Matrix4fc viewRotation,
                                            Vector3dc cameraPosition,
                                            Vector3dc planeOrigin,
                                            Vector3dc planeNormal,
                                            DepthConvention convention) {
        if (projection == null || viewRotation == null || cameraPosition == null
                || planeOrigin == null || planeNormal == null) {
            throw new IllegalArgumentException("Portal clip inputs must not be null");
        }

        // Plane in view space: n·p + d = 0, where p = R * (world - camera).
        final Vector3f normalView = viewRotation.transformDirection(
                new Vector3f((float) planeNormal.x(), (float) planeNormal.y(), (float) planeNormal.z()));
        final Vector3f originView = viewRotation.transformPosition(new Vector3f(
                (float) (planeOrigin.x() - cameraPosition.x()),
                (float) (planeOrigin.y() - cameraPosition.y()),
                (float) (planeOrigin.z() - cameraPosition.z())));
        double d = -normalView.dot(originView);

        // Orient the plane toward the camera. Then the camera side has positive
        // signed distance and the destination side (which must remain visible)
        // has negative signed distance.
        if (d < 0.0) {
            normalView.negate();
            d = -d;
        }
        if (!normalView.isFinite() || !Double.isFinite(d)
                || normalView.lengthSquared() < EPSILON) {
            return new Matrix4f(projection);
        }

        final Matrix4f result = new Matrix4f(projection);
        // JOML's mCR naming means column C, row R. The w row is
        // (m03, m13, m23, m33); the z row is (m02, m12, m22, m32).
        final float wx = projection.m03();
        final float wy = projection.m13();
        final float wz = projection.m23();
        final float ww = projection.m33();
        switch (convention) {
            case ZERO_TO_ONE_REVERSED -> {
                // Reverse Z: near is z' = w. Setting z' = w + plane·p maps the
                // portal plane exactly to the near depth.
                result.m02(wx + (float) normalView.x);
                result.m12(wy + (float) normalView.y);
                result.m22(wz + (float) normalView.z);
                result.m32(ww + (float) d);
            }
            case NEGATIVE_ONE_TO_ONE -> {
                // OpenGL near is z' = -w.
                result.m02(-wx + (float) normalView.x);
                result.m12(-wy + (float) normalView.y);
                result.m22(-wz + (float) normalView.z);
                result.m32(-ww + (float) d);
            }
            case ZERO_TO_ONE -> {
                // Near is z' = 0; the clip plane itself becomes the z row.
                result.m02((float) normalView.x);
                result.m12((float) normalView.y);
                result.m22((float) normalView.z);
                result.m32((float) d);
            }
            default -> throw new IllegalArgumentException("Unknown depth convention");
        }
        return result;
    }

    /** Signed distance to the portal plane in world space. */
    public static double signedDistance(Vector3dc planeOrigin, Vector3dc planeNormal,
                                        Vector3dc point) {
        return new Vector3d(point).sub(planeOrigin).dot(planeNormal);
    }

    /**
     * True when the camera can see the {@code +normal} face; false when it is
     * on the back. Useful for choosing which side of the destination is kept.
     */
    public static boolean cameraOnPositiveSide(Vector3dc cameraPosition,
                                               Vector3dc planeOrigin,
                                               Vector3dc planeNormal) {
        return signedDistance(planeOrigin, planeNormal, cameraPosition) >= 0.0;
    }
}
