package io.mikaple.crythst.integration.minecraft.entity.placement;

import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Pure world-space geometry for placing entities on a physics structure.
 *
 * <p>Structure voxels are not part of Minecraft's block grid. The vanilla
 * use-item paths therefore look at an empty world {@link BlockPos} and either
 * spawn the entity inside the structure or reject it because there is no real
 * support block. This helper derives a stable point on the clicked structure
 * face from the authoritative {@link StructureBlockHitResult}, independent of
 * the real world block grid.</p>
 *
 * <p>All positions are world-space. The face centre is computed by
 * transforming the structure-local voxel face through the structure pose and
 * {@code blockScale}, exactly like
 * {@link io.mikaple.crythst.integration.minecraft.selection.StructureRaycaster}.
 * Keeping the computation here makes all six faces, rotated poses and scaled
 * structures testable without a live level.</p>
 */
public final class StructureEntityPlacementMath {
    /** Default distance from the structure face used for entity anchors. */
    public static final double DEFAULT_SURFACE_EPSILON = 0.05;
    /** Item frame centre is 1/32 block outside its supporting face, like vanilla. */
    public static final double ITEM_FRAME_DEPTH_OFFSET = 0.03125;
    /** Vanilla item frame thickness. */
    public static final double ITEM_FRAME_THICKNESS = 0.0625;
    /** Vanilla item frame width/height. */
    public static final double ITEM_FRAME_SIZE = 0.75;

    private StructureEntityPlacementMath() {
    }

    /**
     * A surface anchor derived from one structure hit.
     *
     * @param hitPoint    the actual ray hit moved {@code epsilon} along the
     *                    real world normal
     * @param facePoint   the centre of the clicked voxel face moved
     *                    {@code epsilon} along the normal; recommended for
     *                    spawn eggs and other block-like entity items
     * @param faceCenter  the exact centre of the clicked voxel face in world
     *                    space
     * @param face        outward cardinal world face
     * @param normal      outward world normal (may be non-cardinal for a
     *                    rotated structure)
     * @param spawnBlockPos nearest real-world block just outside the clicked
     *                    face, used only as the initial vanilla spawn cell
     */
    public record SurfacePoint(Vec3 hitPoint, Vec3 facePoint, Vec3 faceCenter,
                               Direction face, Vec3 normal, BlockPos spawnBlockPos) {
        /** Alias used by spawn-egg/hanging-entity handlers. */
        public Vec3 spawnPoint() {
            return facePoint;
        }
    }

    /**
     * Placement of a hanging entity (item frame or glow frame).
     *
     * @param position        exact entity centre at the structure surface
     * @param attachedBlockPos block position passed to the vanilla entity
     *        constructor/NBT; it is the air cell just outside the surface
     * @param facing          cardinal face the hanging entity is attached to
     * @param boundingBox     orientation-aware vanilla item-frame box
     */
    public record FramePlacement(Vec3 position, BlockPos attachedBlockPos,
                                 Direction facing, AABB boundingBox) {
    }

    /** Computes the default surface anchor for a structure hit. */
    public static SurfacePoint surfacePoint(StructureBlockHitResult hit) {
        return surfacePoint(hit, DEFAULT_SURFACE_EPSILON);
    }

    /**
     * Computes both the actual-hit anchor and the centred face anchor.
     *
     * @param hit     authoritative structure hit
     * @param epsilon small outward offset, normally
     *                {@link #DEFAULT_SURFACE_EPSILON}
     */
    public static SurfacePoint surfacePoint(StructureBlockHitResult hit, double epsilon) {
        if (hit == null) {
            return null;
        }
        final double offset = positiveEpsilon(epsilon);
        final Direction face = hit.worldFace();
        final Vec3 faceCenter = faceCenter(hit.structurePose(), hit.voxelX(), hit.voxelY(),
                hit.voxelZ(), hit.localFace(), hit.blockScale());
        final Vec3 normal = worldNormal(hit.structurePose(), hit.localFace(), face);
        final Vec3 hitPoint = new Vec3(hit.getLocation().x + normal.x * offset,
                hit.getLocation().y + normal.y * offset,
                hit.getLocation().z + normal.z * offset);
        final Vec3 facePoint = faceCenter.add(normal.scale(offset));
        final BlockPos spawnBlockPos = BlockPos.containing(
                faceCenter.x + face.getStepX() * 0.5,
                faceCenter.y + face.getStepY() * 0.5,
                faceCenter.z + face.getStepZ() * 0.5);
        return new SurfacePoint(hitPoint, facePoint, faceCenter, face, normal, spawnBlockPos);
    }

    /**
     * World-space centre of one structure-local voxel face.
     */
    public static Vec3 faceCenter(Transform3d pose, int x, int y, int z,
                                  Direction localFace, double blockScale) {
        final Direction face = localFace == null ? Direction.UP : localFace;
        final double scale = positiveScale(blockScale);
        final Vector3d local = new Vector3d(
                (x + 0.5 + face.getStepX() * 0.5) * scale,
                (y + 0.5 + face.getStepY() * 0.5) * scale,
                (z + 0.5 + face.getStepZ() * 0.5) * scale);
        if (pose == null) {
            return new Vec3(local.x, local.y, local.z);
        }
        final Vector3d world = pose.transformPoint(local);
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * Outward world normal of the clicked face. Uses the structure rotation so
     * a tilted structure gets a true surface normal, falling back to the
     * cardinal world face when no pose is available.
     */
    public static Vec3 worldNormal(Transform3d pose, Direction localFace, Direction fallback) {
        final Direction face = localFace == null ? fallback : localFace;
        if (face == null) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        if (pose == null) {
            return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        }
        final Vector3d normal = pose.transformDirection(new Vector3d(
                face.getStepX(), face.getStepY(), face.getStepZ()));
        if (normal.lengthSquared() < 1.0e-20) {
            return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        }
        normal.normalize();
        return new Vec3(normal.x, normal.y, normal.z);
    }

    /**
     * Exact item-frame centre for a surface anchor. The frame sits 1/32 block
     * outside the clicked face, matching vanilla
     * {@code Vec3.atCenterOf(pos).relative(face, -0.46875)}.
     */
    public static FramePlacement framePlacement(SurfacePoint surface) {
        if (surface == null) {
            return null;
        }
        final Direction facing = surface.face();
        final Vec3 position = surface.faceCenter().add(
                surface.normal().scale(ITEM_FRAME_DEPTH_OFFSET));
        final BlockPos attached = BlockPos.containing(
                surface.faceCenter().x + facing.getStepX() * 0.5,
                surface.faceCenter().y + facing.getStepY() * 0.5,
                surface.faceCenter().z + facing.getStepZ() * 0.5);
        return new FramePlacement(position, attached, facing, frameBoundingBox(position, facing));
    }

    /**
     * Vanilla item-frame bounding box centred on {@code position} and
     * oriented along {@code facing}.
     */
    public static AABB frameBoundingBox(Vec3 position, Direction facing) {
        final Direction face = facing == null ? Direction.SOUTH : facing;
        final double sizeX = face.getAxis() == Direction.Axis.X ? ITEM_FRAME_THICKNESS : ITEM_FRAME_SIZE;
        final double sizeY = face.getAxis() == Direction.Axis.Y ? ITEM_FRAME_THICKNESS : ITEM_FRAME_SIZE;
        final double sizeZ = face.getAxis() == Direction.Axis.Z ? ITEM_FRAME_THICKNESS : ITEM_FRAME_SIZE;
        return AABB.ofSize(position, sizeX, sizeY, sizeZ);
    }

    private static double positiveScale(double blockScale) {
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            return 1.0;
        }
        return blockScale;
    }

    private static double positiveEpsilon(double epsilon) {
        if (!(epsilon > 0.0) || !Double.isFinite(epsilon)) {
            return DEFAULT_SURFACE_EPSILON;
        }
        return Math.min(0.2, Math.max(0.05, epsilon));
    }
}
