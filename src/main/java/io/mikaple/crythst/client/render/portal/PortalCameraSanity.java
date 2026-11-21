package io.mikaple.crythst.client.render.portal;

import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

/**
 * Cheap, allocation-free validation for a camera that will be used to cull
 * and render a portal destination.
 *
 * <p>Vanilla's {@code SectionOcclusionGraph.addSectionsInFrustum} copies the
 * frustum and calls {@code Frustum.offsetToFullyIncludeCameraCube}, whose loop
 * only terminates for a finite camera position with a sane perspective
 * projection and a non-degenerate view rotation. A NaN position, an identity
 * or zero projection, a zero quaternion, or an extremely tiny FOV can otherwise
 * spin the render thread forever. These checks must run <em>before</em> any
 * vanilla culling path is entered.</p>
 *
 * <p>This class deliberately depends only on JOML so it can be unit tested
 * without bootstrapping Minecraft.</p>
 */
public final class PortalCameraSanity {
    /** 1/tan(fovY / 2) must be in this range; normal FOVs are roughly 1..10. */
    private static final double MIN_PERSPECTIVE_SCALE = 1.0e-3;
    private static final double MAX_PERSPECTIVE_SCALE = 1.0e3;
    /** Standard perspective has m23 == -1; orthographic/identity has m23 == 0. */
    private static final double MIN_ABSOLUTE_M23 = 0.1;
    private static final double MAX_ABSOLUTE_M23 = 10.0;
    /** The far-plane term is tiny for large render distances but never zero. */
    private static final double MIN_ABSOLUTE_M32 = 1.0e-12;
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0e-6;
    private static final double MAX_QUATERNION_LENGTH_SQUARED = 1.0e6;
    /** Reject absurd coordinates before they can overflow float math. */
    private static final double MAX_ABSOLUTE_COORDINATE = 1.0e9;

    private PortalCameraSanity() {
    }

    /**
     * Returns true when the camera can safely participate in culling and
     * rendering. This never inspects world state and never loops over sections.
     */
    public static boolean isUsable(Matrix4fc projection, Matrix4fc viewRotation,
                                   Quaternionfc orientation,
                                   double x, double y, double z) {
        if (projection == null || viewRotation == null || orientation == null) {
            return false;
        }
        if (!isFinitePosition(x, y, z)) {
            return false;
        }
        return isSanePerspective(projection)
                && isSaneRotation(viewRotation)
                && isSaneOrientation(orientation);
    }

    public static boolean isFinitePosition(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= MAX_ABSOLUTE_COORDINATE
                && Math.abs(y) <= MAX_ABSOLUTE_COORDINATE
                && Math.abs(z) <= MAX_ABSOLUTE_COORDINATE;
    }

    /**
     * Checks for a finite, non-degenerate perspective projection with a
     * reasonable FOV and aspect ratio. It does not need to know the window size.
     */
    public static boolean isSanePerspective(Matrix4fc projection) {
        if (projection == null || !isFinite(projection)) {
            return false;
        }
        final double m00 = projection.m00();
        final double m11 = projection.m11();
        if (!(m00 > MIN_PERSPECTIVE_SCALE && m00 <= MAX_PERSPECTIVE_SCALE)
                || !(m11 > MIN_PERSPECTIVE_SCALE && m11 <= MAX_PERSPECTIVE_SCALE)) {
            return false;
        }
        final double aspect = m11 / m00;
        if (!Double.isFinite(aspect) || aspect < 1.0e-3 || aspect > 1.0e3) {
            return false;
        }
        final double m23 = Math.abs(projection.m23());
        if (!(m23 >= MIN_ABSOLUTE_M23 && m23 <= MAX_ABSOLUTE_M23)) {
            return false;
        }
        final double m32 = Math.abs(projection.m32());
        return Double.isFinite(m32) && m32 >= MIN_ABSOLUTE_M32;
    }

    /** A finite pure rotation (no scale/reflection) is expected here. */
    public static boolean isSaneRotation(Matrix4fc viewRotation) {
        if (viewRotation == null || !isFinite(viewRotation)) {
            return false;
        }
        final double determinant = viewRotation.determinant();
        return Double.isFinite(determinant) && Math.abs(determinant - 1.0) <= 0.1;
    }

    public static boolean isSaneOrientation(Quaternionfc orientation) {
        if (orientation == null) {
            return false;
        }
        final double x = orientation.x();
        final double y = orientation.y();
        final double z = orientation.z();
        final double w = orientation.w();
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Double.isFinite(w)) {
            return false;
        }
        final double lengthSquared = x * x + y * y + z * z + w * w;
        return Double.isFinite(lengthSquared)
                && lengthSquared >= MIN_QUATERNION_LENGTH_SQUARED
                && lengthSquared <= MAX_QUATERNION_LENGTH_SQUARED;
    }

    public static boolean isFinite(Matrix4fc matrix) {
        if (matrix == null) {
            return false;
        }
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Double.isFinite(matrix.get(column, row))) {
                    return false;
                }
            }
        }
        return true;
    }
}
