package io.mikaple.crythst.integration.minecraft.interaction;

import org.joml.Vector3dc;

/**
 * Pure slab ray/AABB helpers shared by the client interaction raycast and
 * diagnostics. Kept free of Minecraft classes so it can be unit tested.
 */
public final class StructureRayMath {
    private static final double EPSILON = 1.0e-9;

    private StructureRayMath() {
    }

    /**
     * Intersects a ray with an axis-aligned box.
     *
     * @param origin      ray origin
     * @param direction   normalized ray direction
     * @param minX        box minimum X
     * @param minY        box minimum Y
     * @param minZ        box minimum Z
     * @param maxX        box maximum X
     * @param maxY        box maximum Y
     * @param maxZ        box maximum Z
     * @param maxDistance maximum accepted distance
     * @return distance to the entry point in [0, maxDistance], or {@code -1}
     *         on a miss
     */
    public static double rayBox(Vector3dc origin, Vector3dc direction,
                                double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ,
                                double maxDistance) {
        if (maxDistance < 0.0) {
            return -1.0;
        }
        double tMin = 0.0;
        double tMax = maxDistance;

        if (Math.abs(direction.x()) < EPSILON) {
            if (origin.x() < minX || origin.x() > maxX) {
                return -1.0;
            }
        } else {
            double t1 = (minX - origin.x()) / direction.x();
            double t2 = (maxX - origin.x()) / direction.x();
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }

        if (Math.abs(direction.y()) < EPSILON) {
            if (origin.y() < minY || origin.y() > maxY) {
                return -1.0;
            }
        } else {
            double t1 = (minY - origin.y()) / direction.y();
            double t2 = (maxY - origin.y()) / direction.y();
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }

        if (Math.abs(direction.z()) < EPSILON) {
            if (origin.z() < minZ || origin.z() > maxZ) {
                return -1.0;
            }
        } else {
            double t1 = (minZ - origin.z()) / direction.z();
            double t2 = (maxZ - origin.z()) / direction.z();
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }

        return tMin;
    }
}
