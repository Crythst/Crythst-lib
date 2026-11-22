package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.List;

/**
 * Pure geometric resolution of one axis-aligned entity box against a physics
 * structure's merged voxel boxes.
 *
 * <p>This class deliberately knows nothing about Minecraft entities or Bullet
 * bodies: it takes the structure pose, its merged {@link VoxelBox} list and its
 * exact {@link VoxelCellPart} list and returns a translation that moves the
 * entity box out of the structure. That keeps the
 * one-way collision rule easy to test and guarantees that resolving an entity
 * can never apply a force or impulse to the structure body.</p>
 */
public final class EntityStructureSolver {
    private static final double EPSILON = 1.0e-9;
    /** Legacy default: penetration shallower than this is treated as contact. */
    public static final double DEFAULT_SLOP = 1.0e-4;
    private static final double TIE_EPSILON = 1.0e-6;
    /**
     * World-up support is preferred over a shallower sideways exit when the
     * entity is descending or resting. This is the depth up to which a support
     * contact is recognised on top of the configured slop. Callers extend it
     * by the downward speed so a fast landing on a corner still resolves up.
     */
    public static final double DEFAULT_SUPPORT_LIMIT = 0.6;

    private EntityStructureSolver() {
    }

    /**
     * Resolution tuning shared by the server and client passes.
     *
     * @param margin         extra gap left after separation, in metres
     * @param maxBoxes       narrow-phase budget
     * @param slop           penetrations at or below this are resting contact
     *                       and are not corrected
     * @param preferSupport  prefer a world-up exit when the entity is resting
     *                       or descending (see {@link #supportLimit()})
     * @param supportLimit   deepest upward overlap that may be treated as a
     *                       support/step contact, in metres
     */
    public record Policy(double margin, int maxBoxes, double slop,
                         boolean preferSupport, double supportLimit) {
        public Policy {
            margin = Math.max(0.0, margin);
            maxBoxes = Math.max(0, maxBoxes);
            slop = Math.max(0.0, slop);
            supportLimit = Math.max(0.0, supportLimit);
        }

        /** Legacy behaviour without a contact slop or support preference. */
        public static Policy legacy(double margin, int maxBoxes) {
            return new Policy(margin, maxBoxes, DEFAULT_SLOP, false, 0.0);
        }

        /**
         * Gameplay policy: ignore resting contact below the caller's slop and
         * prefer a vertical support exit while the entity is grounded or
         * descending.
         *
         * @param verticalSpeed entity {@code deltaMovement().y}; negative when
         *                      falling
         */
        public static Policy gameplay(double margin, int maxBoxes, double slop,
                                      boolean restingOrDescending, double verticalSpeed) {
            final double extra = Math.max(0.0, -verticalSpeed);
            return new Policy(margin, maxBoxes, slop, restingOrDescending,
                    Math.min(8.0, DEFAULT_SUPPORT_LIMIT + extra));
        }
    }

    /**
     * A resolved translation.
     *
     * @param offset      world-space translation that puts the entity outside
     *                    the structure, including the configured margin
     * @param normal      unit direction from the structure toward the entity
     * @param penetration overlap depth before the margin was added
     * @param support     {@code true} when the exit is world-up and was chosen
     *                    as a support/landing contact
     */
    public record Hit(Vector3d offset, Vector3d normal, double penetration, boolean support) {
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
        return resolve(entityBox, null, pose, boxes, blockScale,
                Policy.legacy(margin, maxBoxes));
    }

    /** Policy-aware variant without a previous-centre hint. */
    public static Hit resolve(Aabb entityBox, Transform3d pose, List<VoxelBox> boxes,
                              double blockScale, Policy policy) {
        return resolve(entityBox, null, pose, boxes, blockScale, policy);
    }

    /**
     * Variant that uses the entity's previous centre to choose the exit side.
     *
     * <p>Without a hint the sign is taken from the current centre, so an entity
     * that crossed the mid-plane of a thin wall between ticks is pushed out the
     * far side. With a hint the correction returns it toward the side it came
     * from, which prevents tunnelling through thin structures.</p>
     *
     * @param previousCenter entity AABB centre at the previous tick, or
     *                       {@code null} to use the current centre
     */
    public static Hit resolve(Aabb entityBox, Vector3d previousCenter, Transform3d pose,
                              List<VoxelBox> boxes, double blockScale, double margin,
                              int maxBoxes) {
        return resolve(entityBox, previousCenter, pose, boxes, blockScale,
                Policy.legacy(margin, maxBoxes));
    }

    /**
     * Policy-aware resolution used by the gameplay passes.
     *
     * @param policy margin, slop, budget and support preference
     */
    public static Hit resolve(Aabb entityBox, Vector3d previousCenter, Transform3d pose,
                              List<VoxelBox> boxes, double blockScale, Policy policy) {
        return resolve(entityBox, previousCenter, pose, boxes, List.of(), blockScale, policy);
    }

    /**
     * Resolves against merged full-cube boxes and exact fractional cell parts.
     *
     * <p>Both lists use structure-local voxel units; each box is scaled by
     * {@code blockScale} and transformed by {@code pose} exactly like a merged
     * box. Parts are used for slabs, stairs, fences and custom collision hooks,
     * while full cubes stay on the cheap merged path. The narrow-phase budget is
     * shared across both lists.</p>
     *
     * @param boxes  merged full-cube collision boxes in unscaled voxels
     * @param parts  fractional collision parts in unscaled voxels
     * @param policy margin, slop, budget and support preference
     */
    public static Hit resolve(Aabb entityBox, Vector3d previousCenter, Transform3d pose,
                              List<VoxelBox> boxes, List<VoxelCellPart> parts,
                              double blockScale, Policy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy is required");
        }
        if ((boxes.isEmpty() && (parts == null || parts.isEmpty()))
                || policy.maxBoxes() <= 0 || blockScale <= 0.0) {
            return null;
        }
        final Resolver resolver = new Resolver(entityBox, previousCenter, pose, blockScale, policy);
        for (VoxelBox box : boxes) {
            resolver.accept(box.minX(), box.minY(), box.minZ(),
                    box.sizeX(), box.sizeY(), box.sizeZ());
            if (resolver.exhausted()) {
                break;
            }
        }
        if (!resolver.exhausted() && parts != null) {
            for (VoxelCellPart part : parts) {
                resolver.accept(part.minX(), part.minY(), part.minZ(),
                        part.sizeX(), part.sizeY(), part.sizeZ());
                if (resolver.exhausted()) {
                    break;
                }
            }
        }
        return resolver.hit();
    }

    /** Margin/budget overload that also accepts exact fractional parts. */
    public static Hit resolve(Aabb entityBox, Transform3d pose, List<VoxelBox> boxes,
                              List<VoxelCellPart> parts, double blockScale,
                              double margin, int maxBoxes) {
        return resolve(entityBox, null, pose, boxes, parts, blockScale,
                Policy.legacy(margin, maxBoxes));
    }

    /**
     * Per-call immutable context plus the accumulated best exit. Kept private so
     * the public API stays a set of pure static functions.
     */
    private static final class Resolver {
        private final Vector3d previousCenter;
        private final Transform3d pose;
        private final double blockScale;
        private final Policy policy;
        private final double safeMargin;
        private final double hx;
        private final double hy;
        private final double hz;
        private final Vector3d center;
        private final Vector3d b0;
        private final Vector3d b1;
        private final Vector3d b2;
        private final Vector3d queryMin;
        private final Vector3d queryMax;
        private final Best globalBest = new Best();
        private final Best boxBest = new Best();
        private final Vector3d localCenter = new Vector3d();
        private final Vector3d boxCenter = new Vector3d();
        private int examined;
        private boolean exhausted;

        private Resolver(Aabb entityBox, Vector3d previousCenter, Transform3d pose,
                         double blockScale, Policy policy) {
            this.previousCenter = previousCenter;
            this.pose = pose;
            this.blockScale = blockScale;
            this.policy = policy;
            this.safeMargin = policy.margin();
            final Vector3d min = entityBox.min();
            final Vector3d max = entityBox.max();
            this.center = entityBox.center();
            this.hx = (max.x - min.x) * 0.5;
            this.hy = (max.y - min.y) * 0.5;
            this.hz = (max.z - min.z) * 0.5;
            final Vector3d[] axes = poseAxes(pose);
            this.b0 = axes[0];
            this.b1 = axes[1];
            this.b2 = axes[2];
            final Aabb localQuery = localQueryBox(pose, entityBox.expand(safeMargin), blockScale);
            this.queryMin = localQuery.min();
            this.queryMax = localQuery.max();
        }

        private boolean exhausted() {
            return exhausted;
        }

        private void accept(double minX, double minY, double minZ,
                            double sizeX, double sizeY, double sizeZ) {
            if (!overlapsLocalQuery(minX, minY, minZ, sizeX, sizeY, sizeZ, queryMin, queryMax)) {
                return;
            }
            if (examined >= policy.maxBoxes()) {
                exhausted = true;
                return;
            }
            examined++;

            final double hbx = sizeX * blockScale * 0.5;
            final double hby = sizeY * blockScale * 0.5;
            final double hbz = sizeZ * blockScale * 0.5;
            localCenter.set(
                    (minX + sizeX * 0.5) * blockScale,
                    (minY + sizeY * 0.5) * blockScale,
                    (minZ + sizeZ * 0.5) * blockScale
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
                return;
            }

            final double dx = center.x - boxCenter.x;
            final double dy = center.y - boxCenter.y;
            final double dz = center.z - boxCenter.z;
            final Vector3d previousDelta = previousCenter == null
                    ? null : new Vector3d(previousCenter).sub(boxCenter);

            // The nine cross axes only reject; they never become the MTV.
            if (separatedOnCrossAxes(dx, dy, dz, hx, hy, hz, hbx, hby, hbz, b0, b1, b2)) {
                return;
            }

            boxBest.reset();
            // Entity face axes (world X/Y/Z).
            if (!considerFaceAxis(dx, dy, dz, hx,
                    hbx * Math.abs(b0.x) + hby * Math.abs(b1.x) + hbz * Math.abs(b2.x),
                    1.0, 0.0, 0.0, boxBest, previousDelta, false)) {
                return;
            }
            final double boxProjectionY = hbx * Math.abs(b0.y)
                    + hby * Math.abs(b1.y) + hbz * Math.abs(b2.y);
            final double overlapY = hy + boxProjectionY - Math.abs(dy);
            // A support contact is an upward escape that is shallow enough to
            // be a floor/step. It wins over a shallower sideways exit, which
            // is what used to shove a landing player off a corner.
            final boolean support = policy.preferSupport()
                    && dy >= 0.0
                    && overlapY > policy.slop()
                    && overlapY <= policy.supportLimit();
            if (!considerFaceAxis(dx, dy, dz, hy, boxProjectionY,
                    0.0, 1.0, 0.0, boxBest, previousDelta, support)) {
                return;
            }
            if (!considerFaceAxis(dx, dy, dz, hz,
                    hbx * Math.abs(b0.z) + hby * Math.abs(b1.z) + hbz * Math.abs(b2.z),
                    0.0, 0.0, 1.0, boxBest, previousDelta, false)) {
                return;
            }

            // Structure face axes.
            if (!considerFaceAxis(dx, dy, dz,
                    hx * Math.abs(b0.x) + hy * Math.abs(b0.y) + hz * Math.abs(b0.z),
                    hbx, b0.x, b0.y, b0.z, boxBest, previousDelta, false)) {
                return;
            }
            if (!considerFaceAxis(dx, dy, dz,
                    hx * Math.abs(b1.x) + hy * Math.abs(b1.y) + hz * Math.abs(b1.z),
                    hby, b1.x, b1.y, b1.z, boxBest, previousDelta, false)) {
                return;
            }
            if (!considerFaceAxis(dx, dy, dz,
                    hx * Math.abs(b2.x) + hy * Math.abs(b2.y) + hz * Math.abs(b2.z),
                    hbz, b2.x, b2.y, b2.z, boxBest, previousDelta, false)) {
                return;
            }

            // A box whose chosen exit is shallower than the contact slop is
            // merely resting against the entity. Skipping it here (rather than
            // inside offer) keeps the support selection intact: a real landing
            // is still resolved even when a different axis of the same box
            // merely grazes the surface.
            if (boxBest.isValid() && boxBest.depth > policy.slop()) {
                globalBest.merge(boxBest);
            }
        }

        private Hit hit() {
            if (!globalBest.isValid() || globalBest.depth <= policy.slop()) {
                return null;
            }
            final Vector3d normal = new Vector3d(globalBest.nx, globalBest.ny, globalBest.nz);
            final Vector3d offset = new Vector3d(normal).mul(globalBest.depth + safeMargin);
            return new Hit(offset, normal, globalBest.depth, globalBest.support);
        }
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
     *
     * <p>Exposed for the {@code /crythst entitydebug} diagnostic so it reports
     * exactly the same query that the resolver uses.</p>
     */
    public static Aabb localQueryBox(Transform3d pose, Aabb worldBox, double blockScale) {
        if (pose == null || worldBox == null || blockScale <= 0.0) {
            throw new IllegalArgumentException("pose, box and a positive blockScale are required");
        }
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

    private static boolean overlapsLocalQuery(double minX, double minY, double minZ,
                                              double sizeX, double sizeY, double sizeZ,
                                              Vector3d queryMin, Vector3d queryMax) {
        return minX <= queryMax.x && minX + sizeX >= queryMin.x
                && minY <= queryMax.y && minY + sizeY >= queryMin.y
                && minZ <= queryMax.z && minZ + sizeZ >= queryMin.z;
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
                                            Best best, Vector3d previousDelta,
                                            boolean support) {
        final double distance = dx * axisX + dy * axisY + dz * axisZ;
        // Unnormalized cross axes are not passed here, so the distance is in
        // metres and can be compared directly with the projected half extents.
        final double overlap = entityProjection + boxProjection - Math.abs(distance);
        if (overlap <= EPSILON) {
            return false;
        }
        double sign = distance >= 0.0 ? 1.0 : -1.0;
        // The previous-centre hint only matters while the entity's centre is
        // still inside this box's slab. Once the centre is clearly outside the
        // box the current side is unambiguous, and a stale/interpolated hint
        // must not push the entity back into the surface.
        if (previousDelta != null && Math.abs(distance) < boxProjection - 1.0e-4) {
            final double previousDistance = previousDelta.x * axisX
                    + previousDelta.y * axisY + previousDelta.z * axisZ;
            // Near a face plane the previous side is numerically ambiguous.
            // Falling back to the current centre prevents float noise from
            // pushing an entity deeper into a wall.
            if (Math.abs(previousDistance) > 1.0e-4) {
                sign = previousDistance >= 0.0 ? 1.0 : -1.0;
            }
        }
        best.offer(overlap, axisX * sign, axisY * sign, axisZ * sign, support);
        return true;
    }

    /** Smallest face-axis overlap for one merged box. */
    private static final class Best {
        double depth = Double.POSITIVE_INFINITY;
        double nx;
        double ny;
        double nz;
        boolean support;

        void reset() {
            depth = Double.POSITIVE_INFINITY;
            nx = 0.0;
            ny = 0.0;
            nz = 0.0;
            support = false;
        }

        void offer(double overlap, double x, double y, double z, boolean support) {
            if (support) {
                // A support exit beats any sideways escape, however shallow
                // the latter is. Otherwise a landing on the edge of a floor is
                // pushed off sideways instead of being placed on top.
                if (!this.support || overlap < depth - TIE_EPSILON) {
                    depth = overlap;
                    nx = x;
                    ny = y;
                    nz = z;
                    this.support = true;
                }
                return;
            }
            if (this.support) {
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
                offer(other.depth, other.nx, other.ny, other.nz, other.support);
            }
        }

        boolean isValid() {
            return depth != Double.POSITIVE_INFINITY;
        }
    }
}
