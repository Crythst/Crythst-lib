package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.List;

/**
 * Pure geometric resolution of one axis-aligned entity box against a physics
 * structure's merged voxel boxes.
 *
 * <p>This class deliberately knows nothing about Minecraft entities or Bullet
 * bodies: it takes the structure pose and its {@link VoxelBox} list and returns
 * a translation that moves the entity box out of the structure. That keeps the
 * one-way collision rule easy to test and guarantees that resolving an entity
 * can never apply a force or impulse to the structure body.</p>
 */
public final class EntityStructureSolver {
    private static final double EPSILON = 1.0e-9;
    /** Penetration shallower than this is treated as resting contact. */
    private static final double MIN_PENETRATION = 1.0e-4;
    private static final double TIE_EPSILON = 1.0e-6;

    private EntityStructureSolver() {
    }

    /**
     * A resolved translation.
     *
     * @param offset      world-space translation that puts the entity outside
     *                    the structure, including the configured margin
     * @param normal      unit direction from the structure toward the entity
     * @param penetration overlap depth before the margin was added
     */
    public record Hit(Vector3d offset, Vector3d normal, double penetration) {
        public Hit {
            offset = new Vector3d(offset);
            normal = new Vector3d(normal);
        }

        @Override
        public Vector3d offset() {
            return new Vector3d(offset);
        }

        @Override
        public Vector3d normal() {
            return new Vector3d(normal);
        }
    }

    /**
     * Finds the smallest face-axis translation that separates {@code entityBox}
     * from every overlapping merged box of the structure.
     *
     * <p>All 15 SAT axes are used to reject non-overlaps, but only the six face
     * normals are used for the MTV. Cross axes are unstable for de-penetration
     * (they can point diagonally out of an edge) and would make entities slide
     * unpredictably along rotated structures.</p>
     *
     * @param entityBox  entity bounds in world space
     * @param pose       current structure pose (local voxel metres to world)
     * @param boxes      merged collision boxes in unscaled voxel coordinates
     * @param blockScale metres per voxel
     * @param margin     extra gap left after separation
     * @param maxBoxes   narrow-phase budget for this call
     * @return the best translation, or {@code null} when the entity is clear
     */
    public static Hit resolve(Aabb entityBox, Transform3d pose, List<VoxelBox> boxes,
                              double blockScale, double margin, int maxBoxes) {
        if (boxes.isEmpty() || maxBoxes <= 0 || blockScale <= 0.0) {
            return null;
        }
        final double safeMargin = Math.max(0.0, margin);
        final Vector3d min = entityBox.min();
        final Vector3d max = entityBox.max();
        final Vector3d center = entityBox.center();
        final double hx = (max.x - min.x) * 0.5;
        final double hy = (max.y - min.y) * 0.5;
        final double hz = (max.z - min.z) * 0.5;

        final Vector3d[] axes = poseAxes(pose);
        final Vector3d b0 = axes[0];
        final Vector3d b1 = axes[1];
        final Vector3d b2 = axes[2];
        final Aabb localQuery = localQuery(pose, entityBox.expand(safeMargin), blockScale);
        final Vector3d queryMin = localQuery.min();
        final Vector3d queryMax = localQuery.max();

        final Best globalBest = new Best();
        final Best boxBest = new Best();
        final Vector3d localCenter = new Vector3d();
        final Vector3d boxCenter = new Vector3d();

        int examined = 0;
        boxLoop:
        for (VoxelBox box : boxes) {
            if (!overlapsLocalQuery(box, queryMin, queryMax)) {
                continue;
            }
            if (examined++ >= maxBoxes) {
                break;
            }

            final double hbx = box.sizeX() * blockScale * 0.5;
            final double hby = box.sizeY() * blockScale * 0.5;
            final double hbz = box.sizeZ() * blockScale * 0.5;
            localCenter.set(
                    (box.minX() + box.sizeX() * 0.5) * blockScale,
                    (box.minY() + box.sizeY() * 0.5) * blockScale,
                    (box.minZ() + box.sizeZ() * 0.5) * blockScale
            );
            pose.transformPoint(localCenter, boxCenter);

            // Cheap world-AABB rejection before the full SAT.
            final double worldHalfX = Math.abs(b0.x) * hbx + Math.abs(b1.x) * hby + Math.abs(b2.x) * hbz;
            final double worldHalfY = Math.abs(b0.y) * hbx + Math.abs(b1.y) * hby + Math.abs(b2.y) * hbz;
            final double worldHalfZ = Math.abs(b0.z) * hbx + Math.abs(b1.z) * hby + Math.abs(b2.z) * hbz;
            if (center.x + hx < boxCenter.x - worldHalfX
                    || center.x - hx > boxCenter.x + worldHalfX
                    || center.y + hy < boxCenter.y - worldHalfY
                    || center.y - hy > boxCenter.y + worldHalfY
                    || center.z + hz < boxCenter.z - worldHalfZ
                    || center.z - hz > boxCenter.z + worldHalfZ) {
                continue;
            }

            final double dx = center.x - boxCenter.x;
            final double dy = center.y - boxCenter.y;
            final double dz = center.z - boxCenter.z;

            // The nine cross axes only reject; they never become the MTV.
            if (separatedOnCrossAxes(dx, dy, dz, hx, hy, hz, hbx, hby, hbz, b0, b1, b2)) {
                continue;
            }

            boxBest.reset();
            // Entity face axes (world X/Y/Z).
            if (!considerFaceAxis(dx, dy, dz, hx,
                    hbx * Math.abs(b0.x) + hby * Math.abs(b1.x) + hbz * Math.abs(b2.x),
                    1.0, 0.0, 0.0, boxBest)) {
                continue boxLoop;
            }
            if (!considerFaceAxis(dx, dy, dz, hy,
                    hbx * Math.abs(b0.y) + hby * Math.abs(b1.y) + hbz * Math.abs(b2.y),
                    0.0, 1.0, 0.0, boxBest)) {
                continue boxLoop;
            }
            if (!considerFaceAxis(dx, dy, dz, hz,
                    hbx * Math.abs(b0.z) + hby * Math.abs(b1.z) + hbz * Math.abs(b2.z),
                    0.0, 0.0, 1.0, boxBest)) {
                continue boxLoop;
            }

            // Structure face axes.
            if (!considerFaceAxis(dx, dy, dz,
                    hx * Math.abs(b0.x) + hy * Math.abs(b0.y) + hz * Math.abs(b0.z),
                    hbx, b0.x, b0.y, b0.z, boxBest)) {
                continue boxLoop;
            }
            if (!considerFaceAxis(dx, dy, dz,
                    hx * Math.abs(b1.x) + hy * Math.abs(b1.y) + hz * Math.abs(b1.z),
                    hby, b1.x, b1.y, b1.z, boxBest)) {
                continue boxLoop;
            }
            if (!considerFaceAxis(dx, dy, dz,
                    hx * Math.abs(b2.x) + hy * Math.abs(b2.y) + hz * Math.abs(b2.z),
                    hbz, b2.x, b2.y, b2.z, boxBest)) {
                continue boxLoop;
            }

            globalBest.merge(boxBest);
        }

        if (!globalBest.isValid()) {
            return null;
        }
        final Vector3d normal = new Vector3d(globalBest.nx, globalBest.ny, globalBest.nz);
        final Vector3d offset = new Vector3d(normal).mul(globalBest.depth + safeMargin);
        return new Hit(offset, normal, globalBest.depth);
    }

    private static Vector3d[] poseAxes(Transform3d pose) {
        final Quaterniondc rotation = pose.rotation();
        return new Vector3d[]{
                new Vector3d(1.0, 0.0, 0.0).rotate(rotation),
                new Vector3d(0.0, 1.0, 0.0).rotate(rotation),
                new Vector3d(0.0, 0.0, 1.0).rotate(rotation)
        };
    }

    /**
     * Conservative local-space query box. The structure pose maps voxel metres
     * to world space, so the inverse plus the block scale yields voxel units.
     */
    private static Aabb localQuery(Transform3d pose, Aabb worldBox, double blockScale) {
        final Transform3d inverse = pose.inverse();
        final Vector3d min = worldBox.min();
        final Vector3d max = worldBox.max();
        final Vector3d point = new Vector3d();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        final double inverseScale = 1.0 / blockScale;
        for (int i = 0; i < 8; i++) {
            point.set(
                    (i & 1) == 0 ? min.x : max.x,
                    (i & 2) == 0 ? min.y : max.y,
                    (i & 4) == 0 ? min.z : max.z
            );
            inverse.transformPoint(point, point);
            final double x = point.x * inverseScale;
            final double y = point.y * inverseScale;
            final double z = point.z * inverseScale;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private static boolean overlapsLocalQuery(VoxelBox box, Vector3d queryMin, Vector3d queryMax) {
        return box.minX() <= queryMax.x && box.minX() + box.sizeX() >= queryMin.x
                && box.minY() <= queryMax.y && box.minY() + box.sizeY() >= queryMin.y
                && box.minZ() <= queryMax.z && box.minZ() + box.sizeZ() >= queryMin.z;
    }

    private static boolean separatedOnCrossAxes(double dx, double dy, double dz,
                                                double hx, double hy, double hz,
                                                double hbx, double hby, double hbz,
                                                Vector3d b0, Vector3d b1, Vector3d b2) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final Vector3d b = j == 0 ? b0 : j == 1 ? b1 : b2;
                final double axisX;
                final double axisY;
                final double axisZ;
                if (i == 0) {
                    // (1,0,0) x b
                    axisX = 0.0;
                    axisY = -b.z;
                    axisZ = b.y;
                } else if (i == 1) {
                    // (0,1,0) x b
                    axisX = b.z;
                    axisY = 0.0;
                    axisZ = -b.x;
                } else {
                    // (0,0,1) x b
                    axisX = -b.y;
                    axisY = b.x;
                    axisZ = 0.0;
                }
                final double lengthSquared = axisX * axisX + axisY * axisY + axisZ * axisZ;
                if (lengthSquared < 1.0e-12) {
                    continue;
                }
                final double distance = dx * axisX + dy * axisY + dz * axisZ;
                final double entityProjection = hx * Math.abs(axisX)
                        + hy * Math.abs(axisY) + hz * Math.abs(axisZ);
                final double boxProjection = hbx * Math.abs(axisX * b0.x + axisY * b0.y + axisZ * b0.z)
                        + hby * Math.abs(axisX * b1.x + axisY * b1.y + axisZ * b1.z)
                        + hbz * Math.abs(axisX * b2.x + axisY * b2.y + axisZ * b2.z);
                if (Math.abs(distance) > entityProjection + boxProjection + EPSILON) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean considerFaceAxis(double dx, double dy, double dz,
                                            double entityProjection, double boxProjection,
                                            double axisX, double axisY, double axisZ,
                                            Best best) {
        final double distance = dx * axisX + dy * axisY + dz * axisZ;
        // Unnormalized cross axes are not passed here, so the distance is in
        // metres and can be compared directly with the projected half extents.
        final double overlap = entityProjection + boxProjection - Math.abs(distance);
        if (overlap <= EPSILON) {
            return false;
        }
        final double sign = distance >= 0.0 ? 1.0 : -1.0;
        best.offer(overlap, axisX * sign, axisY * sign, axisZ * sign);
        return true;
    }

    /** Smallest face-axis overlap for one merged box. */
    private static final class Best {
        double depth = Double.POSITIVE_INFINITY;
        double nx;
        double ny;
        double nz;

        void reset() {
            depth = Double.POSITIVE_INFINITY;
            nx = 0.0;
            ny = 0.0;
            nz = 0.0;
        }

        void offer(double overlap, double x, double y, double z) {
            if (overlap <= MIN_PENETRATION) {
                return;
            }
            // Prefer an upward escape when two axes are equally shallow. This
            // keeps entities resting on top of a structure instead of being
            // squeezed sideways by floating-point ties.
            final boolean better = overlap < depth - TIE_EPSILON
                    || (Math.abs(overlap - depth) <= TIE_EPSILON && y > 0.9 && ny < 0.5);
            if (better) {
                depth = overlap;
                nx = x;
                ny = y;
                nz = z;
            }
        }

        void merge(Best other) {
            if (other.isValid()) {
                offer(other.depth, other.nx, other.ny, other.nz);
            }
        }

        boolean isValid() {
            return depth != Double.POSITIVE_INFINITY;
        }
    }
}
