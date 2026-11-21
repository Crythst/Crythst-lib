package io.mikaple.crythst.integration.minecraft.entity.riding;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Pure geometry and angle helpers for carrying entities on physics structures.
 *
 * <p>Everything here works on immutable pose/collider data. It never touches a
 * Minecraft entity or a Bullet body, which keeps the attachment maths easy to
 * unit test and guarantees the riding pass has no path that can apply a force
 * to a structure.</p>
 *
 * <p>Structure-local coordinates use the same convention as
 * {@link io.mikaple.crythst.physics.structure.PhysicsStructure#pose()}:
 * {@code pose.transformPoint(local)} maps local metres to world space and
 * {@code pose.inverseTransformPoint(world)} maps back. The centre-of-mass
 * offset is already part of that pose.</p>
 */
public final class EntityStructureRidingMath {
    /** Default vertical contact tolerance for "feet are on this surface". */
    public static final double DEFAULT_SUPPORT_TOLERANCE = 0.08;
    /**
     * Carry displacements below this are numerical noise. Applying them with a
     * player teleport every tick zeroes velocity and makes a static structure
     * feel sticky, so they are skipped.
     */
    public static final double CARRY_EPSILON = 1.0e-6;
    /** Yaw changes below this are not worth a teleport packet. */
    public static final float CARRY_YAW_EPSILON = 1.0e-3f;
    /** Horizontal slack at a box edge, in metres. */
    private static final double EDGE_SLACK = 0.02;
    /** A centre hit is enough; otherwise most of the foot corners must agree. */
    private static final int MIN_CORNER_SAMPLES = 3;

    private EntityStructureRidingMath() {
    }

    /**
     * Whether a carry target differs from the entity enough to justify moving
     * it. A static platform therefore produces no teleport at all, while a
     * real pose delta or yaw change is always applied.
     */
    public static boolean needsCarryUpdate(Vector3dc current, Vector3dc target,
                                           float currentYaw, float targetYaw) {
        final double dx = target.x() - current.x();
        final double dy = target.y() - current.y();
        final double dz = target.z() - current.z();
        if (dx * dx + dy * dy + dz * dz > CARRY_EPSILON * CARRY_EPSILON) {
            return true;
        }
        return Math.abs(wrapDegrees(targetYaw - currentYaw)) > CARRY_YAW_EPSILON;
    }

    /**
     * Converts a world point into structure-local metres.
     */
    public static Vector3d toLocal(Transform3d pose, Vector3dc worldPoint) {
        return pose.inverseTransformPoint(worldPoint);
    }

    /**
     * Converts structure-local metres into world space.
     */
    public static Vector3d toWorld(Transform3d pose, Vector3dc localPoint) {
        return pose.transformPoint(localPoint);
    }

    /**
     * Yaw of the structure around its local up axis, in Minecraft degrees.
     *
     * <p>Minecraft yaw {@code 0} looks toward {@code +Z} and {@code 90} toward
     * {@code -X}. For a heavily pitched structure the forward axis becomes
     * vertical, so the local right axis is used instead to keep the result
     * continuous.</p>
     */
    public static double structureYawDegrees(Quaterniondc rotation) {
        final Vector3d forward = new Vector3d(0.0, 0.0, 1.0).rotate(rotation);
        final double horizontal = forward.x * forward.x + forward.z * forward.z;
        if (horizontal > 1.0e-8) {
            return Math.toDegrees(Math.atan2(-forward.x, forward.z));
        }
        final Vector3d right = new Vector3d(1.0, 0.0, 0.0).rotate(rotation);
        return Math.toDegrees(Math.atan2(right.z, right.x));
    }

    /**
     * Wraps an angle to {@code (-180, 180]}.
     */
    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped <= -180.0) {
            wrapped += 360.0;
        } else if (wrapped > 180.0) {
            wrapped -= 360.0;
        }
        return wrapped;
    }

    /**
     * Entity yaw expressed relative to the structure's yaw.
     */
    public static double localYawDegrees(float entityYaw, Quaterniondc structureRotation) {
        return wrapDegrees(entityYaw - structureYawDegrees(structureRotation));
    }

    /**
     * World yaw of an entity that keeps its local yaw on a rotating structure.
     */
    public static double worldYawDegrees(double localYaw, Quaterniondc structureRotation) {
        return wrapDegrees(structureYawDegrees(structureRotation) + localYaw);
    }

    /**
     * Finds a supporting top surface under an entity's feet.
     *
     * <p>The centre of the foot box is always tested. The four inset corners
     * are tested as well, so an entity standing on the edge of a platform stays
     * supported as long as most of its footprint is still over the surface.</p>
     *
     * @param pose       structure pose (local metres to world)
     * @param boxes      merged collider boxes in voxel units
     * @param blockScale metres per voxel
     * @param feetCenter centre of the entity's feet in world space
     * @param halfX      half width of the entity footprint
     * @param halfZ      half depth of the entity footprint
     * @param tolerance  maximum vertical gap, positive or negative
     * @param maxBoxes   narrow-phase budget
     * @return the best support, or {@code null} when no top is close enough
     */
    public static Support findSupport(Transform3d pose, List<VoxelBox> boxes,
                                      double blockScale,
                                      Vector3dc feetCenter, double halfX, double halfZ,
                                      double tolerance, int maxBoxes) {
        return findSupport(pose, boxes, List.of(), blockScale,
                feetCenter, halfX, halfZ, tolerance, maxBoxes);
    }

    /**
     * Footprint support check that also considers exact fractional cell parts.
     *
     * <p>Merged boxes and parts share the same narrow-phase budget. A structure
     * made only of slabs or stairs therefore still counts as support even though
     * it has no merged full-cube box.</p>
     */
    public static Support findSupport(Transform3d pose, List<VoxelBox> boxes,
                                      List<VoxelCellPart> parts, double blockScale,
                                      Vector3dc feetCenter, double halfX, double halfZ,
                                      double tolerance, int maxBoxes) {
        if (pose == null || maxBoxes <= 0
                || blockScale <= 0.0 || !(tolerance >= 0.0) || feetCenter == null) {
            return null;
        }
        final boolean hasBoxes = boxes != null && !boxes.isEmpty();
        final boolean hasParts = parts != null && !parts.isEmpty();
        if (!hasBoxes && !hasParts) {
            return null;
        }
        final double safeHalfX = Math.max(0.0, halfX);
        final double safeHalfZ = Math.max(0.0, halfZ);
        final double insetX = Math.min(EDGE_SLACK, safeHalfX * 0.5);
        final double insetZ = Math.min(EDGE_SLACK, safeHalfZ * 0.5);
        final double cornerX = Math.max(0.0, safeHalfX - insetX);
        final double cornerZ = Math.max(0.0, safeHalfZ - insetZ);

        final double[] offsetsX = {0.0, cornerX, -cornerX, cornerX, -cornerX};
        final double[] offsetsZ = {0.0, cornerZ, cornerZ, -cornerZ, -cornerZ};

        Support center = null;
        Support bestCorner = null;
        int cornerHits = 0;
        final Vector3d sample = new Vector3d();
        for (int i = 0; i < offsetsX.length; i++) {
            sample.set(feetCenter.x() + offsetsX[i], feetCenter.y(), feetCenter.z() + offsetsZ[i]);
            final Support hit = supportAt(pose, boxes, parts, blockScale, sample, tolerance, maxBoxes);
            if (hit == null) {
                continue;
            }
            if (i == 0) {
                center = hit;
            } else {
                cornerHits++;
                if (bestCorner == null
                        || Math.abs(hit.verticalGap()) < Math.abs(bestCorner.verticalGap())) {
                    bestCorner = hit;
                }
            }
        }

        if (center != null) {
            return new Support(center.topY(), center.verticalGap(), 1 + cornerHits);
        }
        if (cornerHits >= MIN_CORNER_SAMPLES && bestCorner != null) {
            return new Support(bestCorner.topY(), bestCorner.verticalGap(), cornerHits);
        }
        return null;
    }

    /**
     * Convenience overload for a point-like entity (no footprint).
     */
    public static Support findSupport(Transform3d pose, List<VoxelBox> boxes,
                                      double blockScale,
                                      Vector3dc feetCenter, double tolerance, int maxBoxes) {
        return findSupport(pose, boxes, blockScale,
                feetCenter, 0.0, 0.0, tolerance, maxBoxes);
    }

    /**
     * Point-like overload that also considers exact fractional cell parts.
     */
    public static Support findSupport(Transform3d pose, List<VoxelBox> boxes,
                                      List<VoxelCellPart> parts, double blockScale,
                                      Vector3dc feetCenter, double tolerance, int maxBoxes) {
        return findSupport(pose, boxes, parts, blockScale,
                feetCenter, 0.0, 0.0, tolerance, maxBoxes);
    }

    private static Support supportAt(Transform3d pose, List<VoxelBox> boxes,
                                     List<VoxelCellPart> parts, double blockScale,
                                     Vector3dc worldSample, double tolerance, int maxBoxes) {
        final Vector3d local = toLocal(pose, worldSample);
        final SupportScan scan = new SupportScan(local, tolerance, maxBoxes);
        if (boxes != null) {
            for (VoxelBox box : boxes) {
                scan.accept(box.minX(), box.minY(), box.minZ(),
                        box.sizeX(), box.sizeY(), box.sizeZ(), blockScale);
                if (scan.exhausted()) {
                    break;
                }
            }
        }
        if (!scan.exhausted() && parts != null) {
            for (VoxelCellPart part : parts) {
                scan.accept(part.minX(), part.minY(), part.minZ(),
                        part.sizeX(), part.sizeY(), part.sizeZ(), blockScale);
                if (scan.exhausted()) {
                    break;
                }
            }
        }
        return scan.result();
    }

    private static final class SupportScan {
        private final Vector3d local;
        private final double tolerance;
        private final int maxBoxes;
        private double bestTop = Double.NaN;
        private double bestGap = Double.POSITIVE_INFINITY;
        private int examined;
        private boolean exhausted;

        private SupportScan(Vector3d local, double tolerance, int maxBoxes) {
            this.local = local;
            this.tolerance = tolerance;
            this.maxBoxes = maxBoxes;
        }

        private boolean exhausted() {
            return exhausted;
        }

        private void accept(double minX, double minY, double minZ,
                            double sizeX, double sizeY, double sizeZ, double blockScale) {
            final double scaledMinX = minX * blockScale;
            final double scaledMinZ = minZ * blockScale;
            final double scaledMaxX = (minX + sizeX) * blockScale;
            final double scaledMaxY = (minY + sizeY) * blockScale;
            final double scaledMaxZ = (minZ + sizeZ) * blockScale;

            if (local.x < scaledMinX - EDGE_SLACK || local.x > scaledMaxX + EDGE_SLACK
                    || local.z < scaledMinZ - EDGE_SLACK || local.z > scaledMaxZ + EDGE_SLACK) {
                return;
            }
            final double gap = local.y - scaledMaxY;
            if (gap < -tolerance || gap > tolerance) {
                return;
            }
            if (examined >= maxBoxes) {
                exhausted = true;
                return;
            }
            examined++;
            if (Math.abs(gap) < Math.abs(bestGap)) {
                bestGap = gap;
                bestTop = scaledMaxY;
            }
        }

        private Support result() {
            if (!Double.isFinite(bestTop)) {
                return null;
            }
            return new Support(bestTop, bestGap, 1);
        }
    }

    /**
     * A supporting surface in structure-local metres.
     *
     * @param topY        local Y of the box top
     * @param verticalGap feet Y minus {@code topY}; positive above the surface
     * @param samples     number of foot samples that found this surface
     */
    public record Support(double topY, double verticalGap, int samples) {
    }
}
