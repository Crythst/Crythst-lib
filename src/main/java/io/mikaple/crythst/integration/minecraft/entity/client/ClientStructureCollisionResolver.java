package io.mikaple.crythst.integration.minecraft.entity.client;

import io.mikaple.crythst.integration.minecraft.entity.EntityStructureSolver;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;

/**
 * Pure client-side policy on top of {@link EntityStructureSolver}.
 *
 * <p>The server resolves entities against the authoritative physics pose and
 * then teleports the player. Client prediction uses the same solver on the
 * client's copy of the structure (interpolated in singleplayer, last synced
 * pose in multiplayer) so the visual position already matches the correction
 * instead of snapping when the server packet arrives.</p>
 *
 * <p>This class deliberately has no Minecraft dependency. It only turns solver
 * hits into bounded corrections: tiny penetrations are ignored to avoid
 * resting-contact jitter, and corrections larger than a configurable limit are
 * discarded so a stale or wildly wrong snapshot can never fling the player.</p>
 */
public final class ClientStructureCollisionResolver {
    /** Corrections below this length are treated as resting contact. */
    public static final double MIN_CORRECTION = 1.0e-4;

    /** Default per-tick correction cap; larger penetrations are left to the server. */
    public static final double DEFAULT_MAX_CORRECTION = 1.0;

    /** Narrow-phase budget per structure, matching the server manager. */
    public static final int DEFAULT_MAX_BOXES = 256;

    /** Iterations for the rare case where one merged box pushes into another. */
    public static final int DEFAULT_MAX_PASSES = 3;

    private ClientStructureCollisionResolver() {
    }

    /**
     * One structure's collision input.
     *
     * @param pose       structure pose (voxel metres to world), not the
     *                   centre-of-mass body pose
     * @param boxes      merged collision boxes in unscaled voxel coordinates
     * @param blockScale metres per voxel
     */
    public record Collider(Transform3d pose, List<VoxelBox> boxes, double blockScale) {
        public Collider {
            pose = Objects.requireNonNull(pose, "pose");
            boxes = List.copyOf(boxes);
            if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
                throw new IllegalArgumentException("blockScale must be positive and finite: " + blockScale);
            }
        }
    }

    /**
     * A bounded world-space correction.
     *
     * @param offset      translation that separates the entity box
     * @param normal      unit direction from the structure toward the entity
     * @param penetration overlap depth before the margin was added
     */
    public record Correction(Vector3d offset, Vector3d normal, double penetration) {
        public Correction {
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
     * Resolves one entity box against one structure.
     *
     * @param entityBox      entity bounds in world space
     * @param previousCenter entity AABB centre at the previous tick, or
     *                       {@code null} to use the current centre
     * @param collider       structure pose and boxes
     * @param margin         gap left after separation
     * @param maxBoxes       narrow-phase box budget
     * @param maxCorrection  largest correction that may be applied; a larger
     *                       hit returns {@code null} so the server stays
     *                       authoritative
     * @return the bounded correction, or {@code null} when clear or skipped
     */
    public static Correction resolve(Aabb entityBox, Vector3d previousCenter, Collider collider,
                                     double margin, int maxBoxes, double maxCorrection) {
        if (entityBox == null || collider == null || !(maxCorrection > 0.0)) {
            return null;
        }
        final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                entityBox, previousCenter, collider.pose(), collider.boxes(),
                collider.blockScale(), Math.max(0.0, margin), Math.max(0, maxBoxes));
        return bound(hit, maxCorrection);
    }

    /**
     * Resolves one entity box against several structures, accumulating the
     * same way as the server collision manager. Each structure may need up to
     * {@code maxPasses} corrections because one merged box can push the entity
     * into another.
     *
     * <p>If the accumulated correction exceeds {@code maxCorrection} the whole
     * result is discarded. Applying only part of it would leave the entity
     * inside a structure while also desynchronising it from the server.</p>
     */
    public static Correction resolve(Aabb entityBox, Vector3d previousCenter, List<Collider> colliders,
                                     double margin, int maxBoxesPerStructure, int maxPasses,
                                     double maxCorrection) {
        if (entityBox == null || colliders == null || colliders.isEmpty() || !(maxCorrection > 0.0)) {
            return null;
        }
        final Vector3d total = new Vector3d();
        final Vector3d lastNormal = new Vector3d();
        double lastPenetration = 0.0;
        boolean touched = false;
        final int passes = Math.max(1, maxPasses);
        final int boxBudget = Math.max(0, maxBoxesPerStructure);
        final double safeMargin = Math.max(0.0, margin);
        final double maxCorrectionSquared = maxCorrection * maxCorrection;

        for (Collider collider : colliders) {
            for (int pass = 0; pass < passes; pass++) {
                final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                        entityBox.offset(total), previousCenter, collider.pose(), collider.boxes(),
                        collider.blockScale(), safeMargin, boxBudget);
                if (hit == null) {
                    break;
                }
                total.add(hit.offset());
                lastNormal.set(hit.normal());
                lastPenetration = hit.penetration();
                touched = true;
                if (total.lengthSquared() > maxCorrectionSquared) {
                    return null;
                }
            }
        }

        if (!touched || total.lengthSquared() < MIN_CORRECTION * MIN_CORRECTION) {
            return null;
        }
        return new Correction(total, lastNormal, lastPenetration);
    }

    private static Correction bound(EntityStructureSolver.Hit hit, double maxCorrection) {
        if (hit == null) {
            return null;
        }
        final double lengthSquared = hit.offset().lengthSquared();
        if (lengthSquared < MIN_CORRECTION * MIN_CORRECTION
                || lengthSquared > maxCorrection * maxCorrection) {
            return null;
        }
        return new Correction(hit.offset(), hit.normal(), hit.penetration());
    }
}
