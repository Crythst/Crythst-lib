package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Vector3d;

/**
 * Pure selection-cell math for physics structures.
 *
 * <p>Ray hits on a +X/+Y/+Z face can land exactly on the boundary. A plain
 * {@code floor(local / scale)} then picks the neighbouring (usually empty)
 * cell. The ray is therefore advanced a small epsilon into the surface before
 * the voxel coordinate is derived. If the resulting cell is not solid, a
 * 3x3x3 neighbourhood around it is searched and the closest solid cell to the
 * stepped hit point is selected.</p>
 *
 * <p>All arithmetic is in structure-local space, so negative coordinates,
 * rotations and non-unit {@code blockScale} are handled naturally.</p>
 */
public final class StructureSelectionMath {
    /** Minimum world-space step into the surface. */
    private static final double MIN_EPSILON = 1.0e-5;
    /** Fraction of one block-cell used as the default step. */
    private static final double EPSILON_FRACTION = 1.0e-4;

    private StructureSelectionMath() {
    }

    @FunctionalInterface
    public interface SolidCell {
        boolean isSolid(int x, int y, int z);
    }

    public record Cell(int x, int y, int z) {
    }

    /**
     * Finds the voxel cell to outline.
     *
     * @param structurePose structure pose (not the COM body pose)
     * @param worldHit      world-space ray hit point
     * @param worldRay      world-space ray direction (need not be normalized)
     * @param blockScale    metres per voxel
     * @param solid         structure-local solidity test
     * @return the selected cell, or {@code null} when no solid cell is found
     */
    public static Cell findCell(Transform3d structurePose, Vector3d worldHit,
                                Vector3d worldRay, double blockScale,
                                SolidCell solid) {
        if (structurePose == null || worldHit == null || worldRay == null || solid == null) {
            return null;
        }
        final double scale = blockScale > 1.0e-9 ? blockScale : 1.0;
        final Vector3d direction = new Vector3d(worldRay);
        if (direction.lengthSquared() < 1.0e-20) {
            return null;
        }
        direction.normalize();

        // Step past the face, along the ray, in world space. Rescaling by the
        // structure pose afterwards makes this work for rotations and negative
        // coordinates.
        final double epsilon = Math.max(MIN_EPSILON, scale * EPSILON_FRACTION);
        final Vector3d insideWorld = new Vector3d(worldHit)
                .fma(epsilon, direction);
        final Vector3d localInside = structurePose.inverseTransformPoint(insideWorld);
        localInside.div(scale);
        final Vector3d localDirection = structurePose.inverseTransformDirection(direction);

        int x = (int) Math.floor(localInside.x);
        int y = (int) Math.floor(localInside.y);
        int z = (int) Math.floor(localInside.z);
        if (solid.isSolid(x, y, z)) {
            return new Cell(x, y, z);
        }

        // Fallback: the stepped cell can still be empty at a corner, on a
        // non-full block, or due to floating-point drift. Search the 3x3x3
        // neighbourhood and prefer the closest solid cell.
        final Vector3d localHit = structurePose.inverseTransformPoint(worldHit);
        localHit.div(scale);
        final double hitX = localHit.x;
        final double hitY = localHit.y;
        final double hitZ = localHit.z;

        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestBehind = Double.NEGATIVE_INFINITY;
        boolean found = false;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    final int cx = x + dx;
                    final int cy = y + dy;
                    final int cz = z + dz;
                    if (!solid.isSolid(cx, cy, cz)) {
                        continue;
                    }

                    final double centerX = cx + 0.5;
                    final double centerY = cy + 0.5;
                    final double centerZ = cz + 0.5;
                    final double ex = centerX - localInside.x;
                    final double ey = centerY - localInside.y;
                    final double ez = centerZ - localInside.z;
                    final double distance = ex * ex + ey * ey + ez * ez;

                    // Positive dot product means the cell is on the far side
                    // of the entry face (inside the solid), which is the
                    // expected side for a ray hit.
                    final double behind = (centerX - hitX) * localDirection.x
                            + (centerY - hitY) * localDirection.y
                            + (centerZ - hitZ) * localDirection.z;

                    if (!found
                            || distance < bestDistance - 1.0e-12
                            || (Math.abs(distance - bestDistance) <= 1.0e-12
                            && behind > bestBehind)) {
                        found = true;
                        bestX = cx;
                        bestY = cy;
                        bestZ = cz;
                        bestDistance = distance;
                        bestBehind = behind;
                    }
                }
            }
        }

        return found ? new Cell(bestX, bestY, bestZ) : null;
    }
}
