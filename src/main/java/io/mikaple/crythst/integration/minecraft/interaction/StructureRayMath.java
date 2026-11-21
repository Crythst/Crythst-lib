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
     * A ray/box intersection with the outward surface normal at the entry
     * point. The normal is zero when the ray starts inside the box.
     */
    public record BoxHit(double distance, double normalX, double normalY, double normalZ) {
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
        final BoxHit hit = rayBoxHit(origin, direction, minX, minY, minZ,
                maxX, maxY, maxZ, maxDistance);
        return hit == null ? -1.0 : hit.distance();
    }

    /**
     * Intersects a ray with an axis-aligned box and reports the entry normal.
     *
     * @return the hit, or {@code null} on a miss
     */
    public static BoxHit rayBoxHit(Vector3dc origin, Vector3dc direction,
                                   double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double maxDistance) {
        if (maxDistance < 0.0) {
            return null;
        }
        double tMin = 0.0;
        double tMax = maxDistance;
        int axis = -1;
        int sign = 0;

        if (Math.abs(direction.x()) < EPSILON) {
            if (origin.x() < minX || origin.x() > maxX) {
                return null;
            }
        } else {
            double t1 = (minX - origin.x()) / direction.x();
            double t2 = (maxX - origin.x()) / direction.x();
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tMin) {
                tMin = t1;
                axis = 0;
                sign = direction.x() > 0.0 ? -1 : 1;
            }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }

        if (Math.abs(direction.y()) < EPSILON) {
            if (origin.y() < minY || origin.y() > maxY) {
                return null;
            }
        } else {
            double t1 = (minY - origin.y()) / direction.y();
            double t2 = (maxY - origin.y()) / direction.y();
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tMin) {
                tMin = t1;
                axis = 1;
                sign = direction.y() > 0.0 ? -1 : 1;
            }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }

        if (Math.abs(direction.z()) < EPSILON) {
            if (origin.z() < minZ || origin.z() > maxZ) {
                return null;
            }
        } else {
            double t1 = (minZ - origin.z()) / direction.z();
            double t2 = (maxZ - origin.z()) / direction.z();
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tMin) {
                tMin = t1;
                axis = 2;
                sign = direction.z() > 0.0 ? -1 : 1;
            }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }

        final double nx = axis == 0 ? sign : 0.0;
        final double ny = axis == 1 ? sign : 0.0;
        final double nz = axis == 2 ? sign : 0.0;
        return new BoxHit(tMin, nx, ny, nz);
    }
}
