package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementMath;
import io.mikaple.crythst.integration.minecraft.interaction.StructureRayMath;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Structure raycast shared by the client {@code Level.clip} extension and the
 * authoritative server interaction pass.
 *
 * <p>The world ray is transformed into structure-local voxel space and traced
 * with a voxel DDA, so the first non-empty cell is found without iterating the
 * whole volume. Every visited solid cell is then tested against its
 * {@link BlockState#getShape} boxes, not against the full {@code [0, 1]^3}
 * cube. A ray through the empty half of a slab, stair or fence therefore passes
 * through, and the reported hit point/face lie on the visible shape. The
 * selected voxel is always the cell whose actual shape was hit, which is also
 * the voxel the outline draws.</p>
 */
public final class StructureRaycaster {
    /** Small step past a boundary so a DDA starts in the cell the ray enters. */
    private static final double NUDGE = 1.0e-7;
    private static final double MIN_INSET = 1.0e-6;
    /** Numerical slack used only for distance ordering. */
    private static final double DISTANCE_EPSILON = 1.0e-9;

    private StructureRaycaster() {
    }

    /**
     * Casts between two world points.
     *
     * @return the nearest structure hit, or {@code null}
     */
    public static @Nullable StructureBlockHitResult nearest(
            Iterable<? extends StructureVoxelView> views, Vec3 from, Vec3 to) {
        final Vec3 delta = to.subtract(from);
        final double length = delta.length();
        if (length < 1.0e-9) {
            return null;
        }
        return nearest(views, from, new Vector3d(delta.x / length, delta.y / length, delta.z / length),
                length);
    }

    /**
     * Casts along a world-space direction.
     *
     * @param direction  ray direction, need not be normalized
     * @param maxDistance maximum world-space distance
     * @return the nearest structure hit, or {@code null}
     */
    public static @Nullable StructureBlockHitResult nearest(
            Iterable<? extends StructureVoxelView> views, Vec3 from,
            Vector3dc direction, double maxDistance) {
        if (views == null || from == null || direction == null || maxDistance <= 0.0) {
            return null;
        }
        final Vector3d worldDirection = new Vector3d(direction);
        if (worldDirection.lengthSquared() < 1.0e-20) {
            return null;
        }
        worldDirection.normalize();
        final Vector3d worldOrigin = new Vector3d(from.x, from.y, from.z);

        StructureBlockHitResult best = null;
        double bestDistance = maxDistance + DISTANCE_EPSILON;

        for (StructureVoxelView view : views) {
            if (view == null || view.closed()) {
                continue;
            }
            final double scale = view.blockScale();
            if (!(scale > 1.0e-9)) {
                continue;
            }
            final Transform3d pose = view.pose();
            final Vector3d localOrigin = pose.inverseTransformPoint(worldOrigin).div(scale);
            final Vector3d localDirection = pose.inverseTransformDirection(worldDirection).div(scale);
            final double worldPerLocal = scale * localDirection.length();
            if (!(worldPerLocal > 1.0e-12)) {
                continue;
            }
            // The DDA works in the unnormalised local ray parameter. Convert
            // the world-space reach into that parameter instead of comparing
            // local distances with a world-space maximum. The tiny slack keeps
            // a surface exactly at the reach selectable after rounding; the
            // world-distance check below still rejects anything beyond the
            // requested reach.
            final LocalHit local = trace(view, localOrigin, localDirection,
                    maxDistance / worldPerLocal + DISTANCE_EPSILON);
            if (local == null) {
                continue;
            }

            final Vector3d localHitPoint = new Vector3d(localOrigin)
                    .fma(local.distance, localDirection);
            final Vector3d worldHit = pose.transformPoint(localHitPoint.mul(scale));
            // Derive the authoritative distance from the actual hit point. This
            // also keeps scale != 1 structures ordered correctly.
            final double worldDistance = worldOrigin.distance(worldHit);
            if (worldDistance > maxDistance + DISTANCE_EPSILON
                    || worldDistance > bestDistance + DISTANCE_EPSILON
                    || (best != null && Math.abs(worldDistance - bestDistance) <= DISTANCE_EPSILON)) {
                continue;
            }

            final Direction worldFace = StructurePlacementMath.worldFace(pose, local.face);
            final Vector3d worldNormal = pose.transformDirection(new Vector3d(
                    local.face.getStepX(), local.face.getStepY(), local.face.getStepZ())).normalize();
            final double inset = Math.max(MIN_INSET, scale * 1.0e-4);
            final BlockPos blockPos = BlockPos.containing(
                    worldHit.x - worldNormal.x * inset,
                    worldHit.y - worldNormal.y * inset,
                    worldHit.z - worldNormal.z * inset);
            final BlockState state = view.blockState(local.x, local.y, local.z);

            best = new StructureBlockHitResult(
                    new Vec3(worldHit.x, worldHit.y, worldHit.z), worldFace, blockPos,
                    view.bodyId(), pose, local.x, local.y, local.z, local.face,
                    scale, state, worldDistance, view.structure());
            bestDistance = worldDistance;
        }
        return best;
    }

    private record LocalHit(double distance, int x, int y, int z, Direction face) {
    }

    /**
     * Amanatides/Woo voxel traversal in structure-local voxel space.
     *
     * <p>{@code maxDistance} is expressed in the local ray parameter, which is
     * not a world distance when {@code blockScale != 1}; the caller converts it
     * once. Each solid cell is tested against its shape boxes; a cell whose
     * shape the ray misses is skipped, so the DDA continues to the next cell.</p>
     */
    private static @Nullable LocalHit trace(StructureVoxelView view, Vector3d origin,
                                            Vector3d direction, double maxDistance) {
        final IntBounds3 bounds = view.bounds();
        if (bounds == null) {
            return null;
        }
        final StructureRayMath.BoxHit box = StructureRayMath.rayBoxHit(
                origin, direction,
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                maxDistance);
        if (box == null) {
            return null;
        }

        final boolean startsInside = box.normalX() == 0.0
                && box.normalY() == 0.0 && box.normalZ() == 0.0;
        Direction face = startsInside
                ? dominantFace(direction)
                : directionFromNormal(box.normalX(), box.normalY(), box.normalZ());
        if (face == null) {
            face = dominantFace(direction);
        }

        final double enter = box.distance();
        // Move one small fraction of a voxel past the boundary for the integer
        // conversion, so the chosen cell does not depend on the reach. A hit
        // that lands exactly at maxDistance still belongs to the cell the ray
        // enters. The shape intersection itself uses the exact, un-nudged ray
        // and maxDistance.
        final double directionLength = direction.length();
        final double start = enter
                + (directionLength > 1.0e-12 ? NUDGE / directionLength : 0.0);
        final double px = origin.x + direction.x * start;
        final double py = origin.y + direction.y * start;
        final double pz = origin.z + direction.z * start;

        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py);
        int z = (int) Math.floor(pz);
        if (!bounds.contains(x, y, z)) {
            return null;
        }

        final int stepX;
        final double tMaxX;
        final double tDeltaX;
        if (Math.abs(direction.x) < 1.0e-12) {
            stepX = 0;
            tMaxX = Double.POSITIVE_INFINITY;
            tDeltaX = Double.POSITIVE_INFINITY;
        } else {
            stepX = direction.x > 0.0 ? 1 : -1;
            tMaxX = ((stepX > 0 ? x + 1 : x) - origin.x) / direction.x;
            tDeltaX = 1.0 / Math.abs(direction.x);
        }
        final int stepY;
        final double tMaxY;
        final double tDeltaY;
        if (Math.abs(direction.y) < 1.0e-12) {
            stepY = 0;
            tMaxY = Double.POSITIVE_INFINITY;
            tDeltaY = Double.POSITIVE_INFINITY;
        } else {
            stepY = direction.y > 0.0 ? 1 : -1;
            tMaxY = ((stepY > 0 ? y + 1 : y) - origin.y) / direction.y;
            tDeltaY = 1.0 / Math.abs(direction.y);
        }
        final int stepZ;
        final double tMaxZ;
        final double tDeltaZ;
        if (Math.abs(direction.z) < 1.0e-12) {
            stepZ = 0;
            tMaxZ = Double.POSITIVE_INFINITY;
            tDeltaZ = Double.POSITIVE_INFINITY;
        } else {
            stepZ = direction.z > 0.0 ? 1 : -1;
            tMaxZ = ((stepZ > 0 ? z + 1 : z) - origin.z) / direction.z;
            tDeltaZ = 1.0 / Math.abs(direction.z);
        }

        double currentMaxX = tMaxX;
        double currentMaxY = tMaxY;
        double currentMaxZ = tMaxZ;
        double cellDistance = enter;

        while (bounds.contains(x, y, z)) {
            if (view.isSolid(x, y, z)) {
                final LocalHit hit = hitCell(view, x, y, z, origin, direction,
                        maxDistance, face);
                if (hit != null) {
                    return hit;
                }
            }
            if (cellDistance >= maxDistance) {
                return null;
            }
            if (currentMaxX <= currentMaxY && currentMaxX <= currentMaxZ) {
                x += stepX;
                cellDistance = currentMaxX;
                currentMaxX += tDeltaX;
                face = stepX > 0 ? Direction.WEST : Direction.EAST;
            } else if (currentMaxY <= currentMaxZ) {
                y += stepY;
                cellDistance = currentMaxY;
                currentMaxY += tDeltaY;
                face = stepY > 0 ? Direction.DOWN : Direction.UP;
            } else {
                z += stepZ;
                cellDistance = currentMaxZ;
                currentMaxZ += tDeltaZ;
                face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
            }
        }
        return null;
    }

    /**
     * Intersects the local ray with the exact shape of one solid cell.
     *
     * <p>A cell without an outline shape falls back to its full cube so a solid
     * voxel never becomes unselectable; cells with a shape only report a hit
     * when the ray actually intersects one of that shape's boxes.</p>
     */
    private static @Nullable LocalHit hitCell(StructureVoxelView view, int x, int y, int z,
                                              Vector3d origin, Vector3d direction,
                                              double maxDistance, Direction cellFace) {
        final VoxelShape shape = StructureVoxelShapes.localShape(view, x, y, z);
        if (shape.isEmpty()) {
            return hitBox(origin, direction,
                    x, y, z, x + 1.0, y + 1.0, z + 1.0,
                    maxDistance, cellFace, x, y, z);
        }

        LocalHit best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        final List<AABB> boxes = shape.toAabbs();
        for (AABB box : boxes) {
            final LocalHit hit = hitBox(origin, direction,
                    x + box.minX, y + box.minY, z + box.minZ,
                    x + box.maxX, y + box.maxY, z + box.maxZ,
                    maxDistance, cellFace, x, y, z);
            if (hit != null && hit.distance < bestDistance) {
                best = hit;
                bestDistance = hit.distance;
            }
        }
        return best;
    }

    private static @Nullable LocalHit hitBox(Vector3d origin, Vector3d direction,
                                             double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ,
                                             double maxDistance, Direction fallbackFace,
                                             int x, int y, int z) {
        final StructureRayMath.BoxHit hit = StructureRayMath.rayBoxHit(
                origin, direction, minX, minY, minZ, maxX, maxY, maxZ, maxDistance);
        if (hit == null) {
            return null;
        }
        Direction face = directionFromNormal(hit.normalX(), hit.normalY(), hit.normalZ());
        if (face == null) {
            face = fallbackFace != null ? fallbackFace : dominantFace(direction);
        }
        return new LocalHit(hit.distance(), x, y, z, face);
    }

    private static @Nullable Direction directionFromNormal(double x, double y, double z) {
        if (x < -0.5) {
            return Direction.WEST;
        }
        if (x > 0.5) {
            return Direction.EAST;
        }
        if (y < -0.5) {
            return Direction.DOWN;
        }
        if (y > 0.5) {
            return Direction.UP;
        }
        if (z < -0.5) {
            return Direction.NORTH;
        }
        if (z > 0.5) {
            return Direction.SOUTH;
        }
        return null;
    }

    /**
     * Face of a voxel a ray is entering when the ray starts inside it. The
     * face opposite the dominant travel axis is the visible surface.
     */
    private static Direction dominantFace(Vector3d direction) {
        final double ax = Math.abs(direction.x);
        final double ay = Math.abs(direction.y);
        final double az = Math.abs(direction.z);
        if (ax >= ay && ax >= az) {
            return direction.x >= 0.0 ? Direction.WEST : Direction.EAST;
        }
        if (ay >= ax && ay >= az) {
            return direction.y >= 0.0 ? Direction.DOWN : Direction.UP;
        }
        return direction.z >= 0.0 ? Direction.NORTH : Direction.SOUTH;
    }
}
