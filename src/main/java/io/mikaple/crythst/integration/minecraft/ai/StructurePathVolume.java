package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * One structure sampled in world block coordinates for pathfinding.
 *
 * <p>A world block position is mapped through the inverse structure pose into
 * structure-local metres, then divided by {@link #blockScale()} and floored to
 * a voxel. That means rotated and scaled structures are supported, but a
 * structure voxel that only partially overlaps a world cell is represented by
 * the state at that cell's centre. This is the same grid approximation vanilla
 * pathfinding already makes; exact OBB collision remains the entity-movement
 * path.</p>
 *
 * <p>Block reads go through {@link StructureLevelView#getBlockState(BlockPos)}.
 * Server views used here are backed by the structure volume/side table rather
 * than the auxiliary ticking level, so a query never loads a chunk.</p>
 */
public final class StructurePathVolume {
    private final long bodyId;
    private final StructureLevelView view;
    private final Transform3d pose;
    private final Transform3d inversePose;
    private final double blockScale;
    private final IntBounds3 bounds;
    private final Aabb worldBounds;
    private final long epoch;
    private final boolean needsCornerSampling;

    private StructurePathVolume(long bodyId, StructureLevelView view, Transform3d pose,
                                Transform3d inversePose, double blockScale,
                                IntBounds3 bounds, Aabb worldBounds, long epoch) {
        if (view == null || pose == null || inversePose == null || bounds == null
                || worldBounds == null) {
            throw new NullPointerException("view/pose/bounds");
        }
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive: " + blockScale);
        }
        this.bodyId = bodyId;
        this.view = view;
        this.pose = pose;
        this.inversePose = inversePose;
        this.blockScale = blockScale;
        this.bounds = bounds;
        this.worldBounds = worldBounds;
        this.epoch = epoch;
        final Quaterniond rotation = pose.rotation();
        final boolean yawOnly = Math.abs(rotation.x()) < 1.0e-6
                && Math.abs(rotation.z()) < 1.0e-6;
        this.needsCornerSampling = blockScale < 1.0 - 1.0e-9 || !yawOnly;
    }

    /** Creates a sampling entry from a live or snapshot structure view. */
    public static StructurePathVolume of(StructureLevelView view) {
        if (view == null) {
            throw new NullPointerException("view");
        }
        final Transform3d pose = view.pose();
        final Transform3d inverse = pose.inverse();
        final IntBounds3 bounds = view.bounds();
        final double scale = view.blockScale();
        final double minX = bounds.minX() * scale;
        final double minY = bounds.minY() * scale;
        final double minZ = bounds.minZ() * scale;
        final double maxX = bounds.maxX() * scale;
        final double maxY = bounds.maxY() * scale;
        final double maxZ = bounds.maxZ() * scale;

        double worldMinX = Double.POSITIVE_INFINITY;
        double worldMinY = Double.POSITIVE_INFINITY;
        double worldMinZ = Double.POSITIVE_INFINITY;
        double worldMaxX = Double.NEGATIVE_INFINITY;
        double worldMaxY = Double.NEGATIVE_INFINITY;
        double worldMaxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d world = pose.transformPoint(new Vector3d(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ));
            worldMinX = Math.min(worldMinX, world.x);
            worldMinY = Math.min(worldMinY, world.y);
            worldMinZ = Math.min(worldMinZ, world.z);
            worldMaxX = Math.max(worldMaxX, world.x);
            worldMaxY = Math.max(worldMaxY, world.y);
            worldMaxZ = Math.max(worldMaxZ, world.z);
        }
        return new StructurePathVolume(view.bodyId(), view, pose, inverse, scale, bounds,
                new Aabb(new Vector3d(worldMinX, worldMinY, worldMinZ),
                        new Vector3d(worldMaxX, worldMaxY, worldMaxZ)),
                view.epoch());
    }

    public long bodyId() {
        return bodyId;
    }

    public StructureLevelView view() {
        return view;
    }

    public Transform3d pose() {
        return pose;
    }

    public double blockScale() {
        return blockScale;
    }

    public IntBounds3 bounds() {
        return bounds;
    }

    public Aabb worldBounds() {
        return worldBounds;
    }

    public long epoch() {
        return epoch;
    }

    /** Whether the underlying structure revision is still current. */
    public boolean isCurrent() {
        return view.isCurrent();
    }

    /**
     * Structure block state at a world block position, or {@code null} when
     * the world cell centre is outside the structure volume.
     */
    public @Nullable BlockState blockStateAtWorld(BlockPos worldPos) {
        BlockState air = null;
        final BlockPos center = localVoxel(worldPos);
        if (center != null) {
            final BlockState state = view.getBlockState(center);
            if (!state.isAir()) {
                return state;
            }
            air = state;
        }
        if (!needsCornerSampling) {
            return air;
        }
        for (int corner = 0; corner < 8; corner++) {
            final BlockPos local = localVoxelAt(worldPos,
                    (corner & 1) == 0 ? 0.0 : 1.0,
                    (corner & 2) == 0 ? 0.0 : 1.0,
                    (corner & 4) == 0 ? 0.0 : 1.0);
            if (local == null) {
                continue;
            }
            final BlockState state = view.getBlockState(local);
            if (!state.isAir()) {
                return state;
            }
            air = state;
        }
        return air;
    }

    /** Fluid state at a world block position, or {@code null} outside. */
    public @Nullable FluidState fluidStateAtWorld(BlockPos worldPos) {
        FluidState empty = null;
        final BlockPos center = localVoxel(worldPos);
        if (center != null) {
            final FluidState fluid = view.getFluidState(center);
            if (!fluid.isEmpty()) {
                return fluid;
            }
            empty = fluid;
        }
        if (!needsCornerSampling) {
            return empty;
        }
        for (int corner = 0; corner < 8; corner++) {
            final BlockPos local = localVoxelAt(worldPos,
                    (corner & 1) == 0 ? 0.0 : 1.0,
                    (corner & 2) == 0 ? 0.0 : 1.0,
                    (corner & 4) == 0 ? 0.0 : 1.0);
            if (local == null) {
                continue;
            }
            final FluidState fluid = view.getFluidState(local);
            if (!fluid.isEmpty()) {
                return fluid;
            }
            empty = fluid;
        }
        return empty;
    }

    /**
     * Nearest structure-local voxel for the centre of a world block, or
     * {@code null} outside {@link #bounds()}.
     */
    public @Nullable BlockPos localVoxel(BlockPos worldPos) {
        return localVoxelAt(worldPos, 0.5, 0.5, 0.5);
    }

    private @Nullable BlockPos localVoxelAt(BlockPos worldPos, double fractionX,
                                            double fractionY, double fractionZ) {
        return localVoxel(new Vector3d(
                worldPos.getX() + fractionX,
                worldPos.getY() + fractionY,
                worldPos.getZ() + fractionZ), inversePose, blockScale, bounds);
    }

    /**
     * Pure coordinate mapping shared with tests and diagnostics.
     *
     * @param worldPos    world block position
     * @param inversePose world-to-local transform
     * @param blockScale  local metres per voxel
     * @param bounds      local integer voxel bounds, max-exclusive
     */
    public static @Nullable BlockPos localVoxel(BlockPos worldPos, Transform3d inversePose,
                                                double blockScale, IntBounds3 bounds) {
        if (worldPos == null) {
            throw new NullPointerException("worldPos");
        }
        return localVoxel(new Vector3d(
                worldPos.getX() + 0.5,
                worldPos.getY() + 0.5,
                worldPos.getZ() + 0.5), inversePose, blockScale, bounds);
    }

    /** Pure mapping for a continuous world point. */
    public static @Nullable BlockPos localVoxel(Vector3d worldPoint, Transform3d inversePose,
                                                double blockScale, IntBounds3 bounds) {
        if (worldPoint == null) {
            throw new NullPointerException("worldPoint");
        }
        if (inversePose == null || bounds == null) {
            throw new NullPointerException("inversePose/bounds");
        }
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            return null;
        }
        final Vector3d local = inversePose.transformPoint(worldPoint);
        final int x = (int) Math.floor(local.x / blockScale);
        final int y = (int) Math.floor(local.y / blockScale);
        final int z = (int) Math.floor(local.z / blockScale);
        return bounds.contains(x, y, z) ? new BlockPos(x, y, z) : null;
    }

    /** Whether a world column intersects the structure's broad-phase bounds. */
    public boolean affectsColumn(int x, int z) {
        return worldBounds.max().x() >= x && worldBounds.min().x() <= x + 1.0
                && worldBounds.max().z() >= z && worldBounds.min().z() <= z + 1.0;
    }
}
