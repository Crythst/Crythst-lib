package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Pure validation for placing one block into a physics-structure voxel.
 *
 * <p>The rules mirror vanilla {@code BlockItem.place}: the target must be a
 * replaceable cell, the state must survive at the virtual position, and the
 * placed collision shape must not obstruct an entity. In addition, a structure
 * target must be exposed by a solid structure voxel (or itself be a valid
 * replaceable cell), so a malformed ray cannot select an arbitrary empty cell
 * deep inside a structure.</p>
 *
 * <p>The class does not mutate anything. Callers validate first and only then
 * expand/copy the volume, reindex the body, play sounds or consume the stack.</p>
 */
public final class StructurePlacementValidation {
    private static final double SAT_EPSILON = 1.0e-7;

    public enum Failure {
        NONE,
        INVALID_STATE,
        NOT_ADJACENT,
        SOLID_TARGET,
        NOT_REPLACEABLE,
        UNSUPPORTED,
        CANNOT_SURVIVE,
        OBSTRUCTED
    }

    public record Result(boolean valid, Failure failure) {
        public static final Result VALID = new Result(true, Failure.NONE);

        public static Result failed(Failure failure) {
            return new Result(false, failure);
        }
    }

    private StructurePlacementValidation() {
    }

    /**
     * Validates one placement.
     *
     * @param placedState   resolved state, including the stack's block-state
     *                      component
     * @param volume        structure volume (solidity and bounds)
     * @param virtualLevel  structure-local {@link LevelReader}
     * @param context       placement context, mainly for player-aware
     *                      replacement/shape rules; may be {@code null} in
     *                      pure tests
     * @param targetX       structure-local target voxel
     * @param targetY       structure-local target voxel
     * @param targetZ       structure-local target voxel
     * @param supportX      structure-local clicked voxel
     * @param supportY      structure-local clicked voxel
     * @param supportZ      structure-local clicked voxel
     * @param entityBoxes   world-space bounding boxes of entities that block
     *                      placement (the caller decides which entities count)
     * @param pose          structure pose
     * @param blockScale    metres per structure voxel
     */
    public static Result validate(BlockState placedState, VoxelVolume volume,
                                  LevelReader virtualLevel,
                                  BlockPlaceContext context,
                                  int targetX, int targetY, int targetZ,
                                  int supportX, int supportY, int supportZ,
                                  Iterable<AABB> entityBoxes,
                                  Transform3d pose, double blockScale) {
        if (placedState == null || placedState.isAir() || virtualLevel == null || volume == null) {
            return Result.failed(Failure.INVALID_STATE);
        }

        final int dx = targetX - supportX;
        final int dy = targetY - supportY;
        final int dz = targetZ - supportZ;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return Result.failed(Failure.NOT_ADJACENT);
        }

        final boolean inside = volume.bounds().contains(targetX, targetY, targetZ);
        if (inside && volume.isSolid(targetX, targetY, targetZ)) {
            return Result.failed(Failure.SOLID_TARGET);
        }

        final BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
        final BlockState existing;
        final boolean replaceable;
        try {
            existing = virtualLevel.getBlockState(targetPos);
            replaceable = isReplaceable(existing, context);
        } catch (RuntimeException e) {
            return Result.failed(Failure.NOT_REPLACEABLE);
        }
        if (!replaceable) {
            return Result.failed(Failure.NOT_REPLACEABLE);
        }

        final boolean supportSolid = volume.bounds().contains(supportX, supportY, supportZ)
                && volume.isSolid(supportX, supportY, supportZ);
        // The normal case is an empty cell exposed by the clicked solid voxel.
        // A non-solid support is only accepted when the target itself is a
        // replaceable (water/grass) cell that still touches solid structure;
        // this prevents a malformed ray from selecting arbitrary empty cells.
        final boolean replaceableCell = existing != null && !existing.isAir();
        if (!supportSolid
                && !(replaceableCell && hasSolidNeighbor(volume, targetX, targetY, targetZ))) {
            return Result.failed(Failure.UNSUPPORTED);
        }

        try {
            if (!placedState.canSurvive(virtualLevel, targetPos)) {
                return Result.failed(Failure.CANNOT_SURVIVE);
            }
        } catch (RuntimeException e) {
            // A modded canSurvive that throws must reject, not crash the
            // authoritative placement path.
            return Result.failed(Failure.CANNOT_SURVIVE);
        }

        final VoxelShape shape;
        try {
            shape = placedState.getCollisionShape(virtualLevel, targetPos,
                    collisionContext(context));
        } catch (RuntimeException e) {
            return Result.failed(Failure.CANNOT_SURVIVE);
        }
        if (!shape.isEmpty() && entityBoxes != null) {
            for (AABB entityBox : entityBoxes) {
                if (entityBox != null
                        && shapeIntersectsAabb(shape, targetX, targetY, targetZ,
                        pose, blockScale, entityBox)) {
                    return Result.failed(Failure.OBSTRUCTED);
                }
            }
        }
        return Result.VALID;
    }

    /**
     * Whether an existing state may be replaced by a new placement. Air, the
     * vanilla replaceable tag, block-specific replacement rules and water are
     * accepted; a solid occupied cell is rejected before this method is called.
     */
    public static boolean isReplaceable(BlockState state, BlockPlaceContext context) {
        if (state == null || state.isAir()) {
            return true;
        }
        if (state.is(BlockTags.REPLACEABLE)) {
            return true;
        }
        if (state.canBeReplaced()) {
            return true;
        }
        if (context != null && state.canBeReplaced(context)) {
            return true;
        }
        final FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    private static boolean hasSolidNeighbor(VoxelVolume volume, int x, int y, int z) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            final int nx = x + direction.getStepX();
            final int ny = y + direction.getStepY();
            final int nz = z + direction.getStepZ();
            if (volume.bounds().contains(nx, ny, nz) && volume.isSolid(nx, ny, nz)) {
                return true;
            }
        }
        return false;
    }

    private static CollisionContext collisionContext(BlockPlaceContext context) {
        return context != null && context.getPlayer() != null
                ? CollisionContext.placementContext(context.getPlayer())
                : CollisionContext.empty();
    }

    /**
     * Whether a real host-world block collision overlaps the world-space cell
     * of a structure voxel. The auxiliary level only sees copied structure
     * blocks, so without this check a structure embedded in terrain could
     * place a block through a real wall/floor.
     */
    public static boolean worldObstructed(ServerLevel level, Transform3d pose,
                                          double blockScale, int x, int y, int z) {
        if (level == null) {
            return false;
        }
        final AABB bounds = worldVoxelBounds(pose, x, y, z, blockScale);
        final BlockPos min = new BlockPos(
                (int) Math.floor(bounds.minX + 1.0e-6),
                (int) Math.floor(bounds.minY + 1.0e-6),
                (int) Math.floor(bounds.minZ + 1.0e-6));
        final BlockPos max = new BlockPos(
                (int) Math.floor(bounds.maxX - 1.0e-6),
                (int) Math.floor(bounds.maxY - 1.0e-6),
                (int) Math.floor(bounds.maxZ - 1.0e-6));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            final VoxelShape shape = level.getBlockState(pos)
                    .getCollisionShape(level, pos, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            for (AABB local : shape.toAabbs()) {
                final AABB block = local.move(pos);
                // Require a real overlap volume, not a shared face/corner. An
                // inflated AABB reports the floor directly under a resting
                // structure as an obstruction because of floating-point noise.
                final double overlapX = Math.min(bounds.maxX, block.maxX)
                        - Math.max(bounds.minX, block.minX);
                final double overlapY = Math.min(bounds.maxY, block.maxY)
                        - Math.max(bounds.minY, block.minY);
                final double overlapZ = Math.min(bounds.maxZ, block.maxZ)
                        - Math.max(bounds.minZ, block.minZ);
                if (overlapX > 1.0e-5 && overlapY > 1.0e-5 && overlapZ > 1.0e-5) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * World-space bounds of one structure voxel. Used as a broad-phase query
     * for entities; exact shape intersection is checked afterwards.
     */
    public static AABB worldVoxelBounds(Transform3d pose, int x, int y, int z,
                                        double blockScale) {
        final double scale = positiveScale(blockScale);
        final Quaterniond rotation = pose == null ? new Quaterniond() : pose.rotation();
        final Vector3d position = pose == null ? new Vector3d() : pose.position();
        final Vector3d min = new Vector3d(Double.POSITIVE_INFINITY);
        final Vector3d max = new Vector3d(Double.NEGATIVE_INFINITY);
        final Vector3d corner = new Vector3d();
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    corner.set((x + cx) * scale, (y + cy) * scale, (z + cz) * scale)
                            .rotate(rotation)
                            .add(position);
                    min.min(corner);
                    max.max(corner);
                }
            }
        }
        return new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
    }

    /**
     * Exact collision-shape test: every {@link VoxelShape} box is treated as an
     * oriented box in the structure pose and checked against the world-space
     * entity box. A pure axis-aligned enclosing box would over-reject rotated
     * slabs, stairs and fences.
     */
    public static boolean shapeIntersectsAabb(VoxelShape shape, int x, int y, int z,
                                              Transform3d pose, double blockScale,
                                              AABB entityBox) {
        if (shape == null || shape.isEmpty() || entityBox == null) {
            return false;
        }
        final double scale = positiveScale(blockScale);
        final Quaterniond rotation = pose == null ? new Quaterniond() : pose.rotation();
        final Vector3d position = pose == null ? new Vector3d() : pose.position();
        final Vector3d u0 = new Vector3d(1.0, 0.0, 0.0).rotate(rotation);
        final Vector3d u1 = new Vector3d(0.0, 1.0, 0.0).rotate(rotation);
        final Vector3d u2 = new Vector3d(0.0, 0.0, 1.0).rotate(rotation);
        for (AABB local : shape.toAabbs()) {
            final double minX = (local.minX + x) * scale;
            final double minY = (local.minY + y) * scale;
            final double minZ = (local.minZ + z) * scale;
            final double maxX = (local.maxX + x) * scale;
            final double maxY = (local.maxY + y) * scale;
            final double maxZ = (local.maxZ + z) * scale;
            final Vector3d center = new Vector3d(
                    (minX + maxX) * 0.5,
                    (minY + maxY) * 0.5,
                    (minZ + maxZ) * 0.5).rotate(rotation).add(position);
            final Vector3d half = new Vector3d(
                    (maxX - minX) * 0.5,
                    (maxY - minY) * 0.5,
                    (maxZ - minZ) * 0.5);
            if (obbIntersectsAabb(center, half, u0, u1, u2, entityBox)) {
                return true;
            }
        }
        return false;
    }

    private static boolean obbIntersectsAabb(Vector3d center, Vector3d half,
                                             Vector3d u0, Vector3d u1, Vector3d u2,
                                             AABB box) {
        final double boxCx = (box.minX + box.maxX) * 0.5;
        final double boxCy = (box.minY + box.maxY) * 0.5;
        final double boxCz = (box.minZ + box.maxZ) * 0.5;
        final double boxHx = (box.maxX - box.minX) * 0.5;
        final double boxHy = (box.maxY - box.minY) * 0.5;
        final double boxHz = (box.maxZ - box.minZ) * 0.5;
        final Vector3d d = new Vector3d(boxCx - center.x, boxCy - center.y, boxCz - center.z);

        // OBB axes.
        if (separated(d.dot(u0), half.x,
                boxHx * Math.abs(u0.x) + boxHy * Math.abs(u0.y) + boxHz * Math.abs(u0.z))) {
            return false;
        }
        if (separated(d.dot(u1), half.y,
                boxHx * Math.abs(u1.x) + boxHy * Math.abs(u1.y) + boxHz * Math.abs(u1.z))) {
            return false;
        }
        if (separated(d.dot(u2), half.z,
                boxHx * Math.abs(u2.x) + boxHy * Math.abs(u2.y) + boxHz * Math.abs(u2.z))) {
            return false;
        }

        // World axes.
        if (separated(Math.abs(d.x), boxHx,
                half.x * Math.abs(u0.x) + half.y * Math.abs(u1.x) + half.z * Math.abs(u2.x))) {
            return false;
        }
        if (separated(Math.abs(d.y), boxHy,
                half.x * Math.abs(u0.y) + half.y * Math.abs(u1.y) + half.z * Math.abs(u2.y))) {
            return false;
        }
        if (separated(Math.abs(d.z), boxHz,
                half.x * Math.abs(u0.z) + half.y * Math.abs(u1.z) + half.z * Math.abs(u2.z))) {
            return false;
        }

        // Cross products of each OBB axis with each world axis.
        final Vector3d[] axes = {u0, u1, u2};
        final double[] extents = {half.x, half.y, half.z};
        for (int i = 0; i < 3; i++) {
            if (crossSeparated(axes[i], new Vector3d(1.0, 0.0, 0.0), axes, extents,
                    d, boxHx, boxHy, boxHz)) {
                return false;
            }
            if (crossSeparated(axes[i], new Vector3d(0.0, 1.0, 0.0), axes, extents,
                    d, boxHx, boxHy, boxHz)) {
                return false;
            }
            if (crossSeparated(axes[i], new Vector3d(0.0, 0.0, 1.0), axes, extents,
                    d, boxHx, boxHy, boxHz)) {
                return false;
            }
        }
        return true;
    }

    private static boolean crossSeparated(Vector3d obbAxis, Vector3d worldAxis,
                                          Vector3d[] axes, double[] extents,
                                          Vector3d d, double boxHx, double boxHy,
                                          double boxHz) {
        final Vector3d axis = new Vector3d(obbAxis).cross(worldAxis);
        if (axis.lengthSquared() < 1.0e-20) {
            return false;
        }
        final double projection = Math.abs(d.dot(axis));
        final double obbRadius = extents[0] * Math.abs(axes[0].dot(axis))
                + extents[1] * Math.abs(axes[1].dot(axis))
                + extents[2] * Math.abs(axes[2].dot(axis));
        final double boxRadius = boxHx * Math.abs(axis.x)
                + boxHy * Math.abs(axis.y)
                + boxHz * Math.abs(axis.z);
        return separated(projection, obbRadius, boxRadius);
    }

    private static boolean separated(double projection, double radiusA, double radiusB) {
        return projection > radiusA + radiusB - SAT_EPSILON;
    }

    private static double positiveScale(double value) {
        return value > 0.0 && Double.isFinite(value) ? value : 1.0;
    }

    /**
     * Convenience overload for callers that do not need a player context.
     */
    public static Result validate(BlockState placedState, VoxelVolume volume,
                                  LevelReader virtualLevel,
                                  int targetX, int targetY, int targetZ,
                                  int supportX, int supportY, int supportZ,
                                  Iterable<AABB> entityBoxes,
                                  Transform3d pose, double blockScale) {
        return validate(placedState, volume, virtualLevel, null,
                targetX, targetY, targetZ, supportX, supportY, supportZ,
                entityBoxes, pose, blockScale);
    }
}
