package io.mikaple.crythst.integration.minecraft.gameevent;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pure impulse selection for structure voxels removed by an explosion.
 *
 * <p>The impulse is radial from the explosion centre to the voxel centre. Its
 * magnitude scales with the voxel mass and the ray power that reached the
 * voxel. Blocks exactly at the centre receive a small upward impulse instead
 * of a zero vector, so a fully enclosed blast still disturbs the body.</p>
 */
public final class ExplosionImpulse {
    /**
     * Velocity change, in metres per second, applied per unit of block mass
     * before the radial falloff. The value is deliberately in the same order
     * as the mining reaction impulse used by the interaction layer.
     */
    public static final double BASE_SPEED = 1.6;

    private ExplosionImpulse() {
    }

    /** Radial unit direction from the explosion centre to a voxel centre. */
    public static Vector3d direction(Vector3dc center, Vector3dc blockCenter) {
        final Vector3d radial = new Vector3d(blockCenter).sub(center);
        if (radial.lengthSquared() < 1.0e-20) {
            return new Vector3d(0.0, 1.0, 0.0);
        }
        return radial.normalize();
    }

    /**
     * Impulse magnitude in {@code N·s}.
     *
     * @param mass  voxel mass in kilograms
     * @param power remaining ray power at the voxel
     * @param radius explosion radius
     * @return non-negative magnitude
     */
    public static double magnitude(double mass, float power, double radius) {
        if (mass <= 0.0 || radius <= 0.0) {
            return 0.0;
        }
        final double normalized = Math.max(0.0, Math.min(1.0, power / radius));
        return mass * BASE_SPEED * (0.5 + 0.5 * normalized);
    }

    /**
     * Approximate ray power for a block-position list produced by vanilla,
     * where the per-ray power is not exposed. Linear falloff from the centre
     * is enough for the secondary void-explosion impulse pass.
     */
    public static float approximatePower(double radius, double distance) {
        if (radius <= 0.0) {
            return 0.0F;
        }
        final double normalized = Math.max(0.0, 1.0 - distance / (radius * 2.0));
        return (float) (radius * normalized);
    }

    /**
     * Radial impulse vector for one destroyed voxel.
     *
     * @return zero vector when the voxel has no mass or the radius is invalid
     */
    public static Vector3d impulse(Vector3dc center, Vector3dc blockCenter,
                                   double mass, float power, double radius) {
        final double magnitude = magnitude(mass, power, radius);
        if (magnitude <= 0.0) {
            return new Vector3d();
        }
        return direction(center, blockCenter).mul(magnitude);
    }
}
