package io.mikaple.crythst.math;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Small math helpers used across the library. All methods are pure.
 */
public final class CrythstMath {
    public static final double EPSILON = 1.0e-9;
    public static final double DEG_TO_RAD = Math.PI / 180.0;
    public static final double RAD_TO_DEG = 180.0 / Math.PI;

    private CrythstMath() {
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double smoothStep(double t) {
        final double x = clamp(t, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    public static boolean isFinite(Vector3dc v) {
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    /**
     * Builds the inverse of a symmetric positive-definite inertia tensor.
     *
     * @return a new matrix, or {@code null} if the tensor is singular
     */
    public static Matrix3d invertInertia(Matrix3d inertia) {
        final double determinant = inertia.determinant();
        if (Math.abs(determinant) < 1.0e-12 || !Double.isFinite(determinant)) {
            return null;
        }
        return new Matrix3d(inertia).invert();
    }

    public static double squaredDistance(Vector3dc a, Vector3dc b) {
        final double dx = a.x() - b.x();
        final double dy = a.y() - b.y();
        final double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    public static Vector3d safeNormalize(Vector3dc v, Vector3d dest) {
        final double lengthSquared = v.x() * v.x() + v.y() * v.y() + v.z() * v.z();
        if (lengthSquared < EPSILON) {
            return dest.set(0.0, 1.0, 0.0);
        }
        return dest.set(v).mul(1.0 / Math.sqrt(lengthSquared));
    }
}
