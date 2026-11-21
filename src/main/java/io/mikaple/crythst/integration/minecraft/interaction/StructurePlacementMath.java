package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pure world-space geometry for placing into a physics structure.
 *
 * <p>Structure voxels do not live in Minecraft's block grid, but
 * {@link net.minecraft.world.item.context.BlockPlaceContext} and every
 * {@code Block#getStateForPlacement} implementation expect a world-space
 * {@link BlockHitResult}. The helpers here convert the structure-local hit
 * face and point into a synthetic, vanilla-shaped click without touching the
 * structure volume. Keeping the conversion separate makes the face/position
 * rules testable without a running client or server.</p>
 */
public final class StructurePlacementMath {
    /**
     * How far behind the clicked face the synthetic block position is moved.
     * Vanilla's hit location lies exactly on the face plane (for example
     * {@code x == 6.0} for the east face of block {@code x == 5}); flooring
     * that boundary would select the empty neighbouring cell and turn a
     * click fraction of {@code 0.0} into {@code 1.0}.
     */
    private static final double FACE_EPSILON = 1.0e-4;

    private StructurePlacementMath() {
    }

    /**
     * Transforms a structure-local cardinal face into the closest world-space
     * cardinal direction. This is the face used for both the adjacent voxel
     * and the synthetic {@link BlockHitResult}.
     */
    public static Direction worldFace(Transform3d structurePose, Direction localFace) {
        if (localFace == null) {
            return Direction.UP;
        }
        if (structurePose == null) {
            return localFace;
        }
        final Vector3d world = new Vector3d(
                localFace.getStepX(),
                localFace.getStepY(),
                localFace.getStepZ()
        );
        return nearestDirection(structurePose.transformDirection(world));
    }

    /**
     * Transforms a world-space normal into the closest cardinal direction.
     */
    public static Direction nearestDirection(Vector3d normal) {
        if (normal == null) {
            return Direction.UP;
        }
        final double ax = Math.abs(normal.x);
        final double ay = Math.abs(normal.y);
        final double az = Math.abs(normal.z);
        if (ax < 1.0e-9 && ay < 1.0e-9 && az < 1.0e-9) {
            return Direction.UP;
        }
        if (ax >= ay && ax >= az) {
            return normal.x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= ax && ay >= az) {
            return normal.y >= 0.0 ? Direction.UP : Direction.DOWN;
        }
        return normal.z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Creates the vanilla-shaped click used for placement-state resolution.
     *
     * @param worldHit  world-space point on the structure voxel surface
     * @param worldFace outward world-space face of the hit voxel
     * @return a hit result with {@code inside == false}
     */
    public static BlockHitResult blockHit(Vector3d worldHit, Direction worldFace) {
        final Vec3 location = new Vec3(worldHit.x, worldHit.y, worldHit.z);
        // Move just behind the face so BlockPos.containing picks the voxel
        // that was clicked rather than the empty cell outside it.
        final double insideX = worldHit.x - worldFace.getStepX() * FACE_EPSILON;
        final double insideY = worldHit.y - worldFace.getStepY() * FACE_EPSILON;
        final double insideZ = worldHit.z - worldFace.getStepZ() * FACE_EPSILON;
        final BlockPos pos = BlockPos.containing(insideX, insideY, insideZ);
        return new BlockHitResult(location, worldFace, pos, false);
    }

    /**
     * Converts a world-space point to structure-local voxel metres.
     *
     * <p>The block scale is part of the coordinate conversion, not only of the
     * render pose: a structure with {@code blockScale == 2} has two-metre
     * voxels, so a world click at the centre of the first voxel is local
     * {@code (1, 1, 1)} rather than {@code (0.5, 0.5, 0.5)}.</p>
     */
    public static Vector3d localPoint(Transform3d pose, Vector3dc worldPoint, double blockScale) {
        final double scale = positiveScale(blockScale);
        final Vector3d local = pose == null
                ? new Vector3d(worldPoint)
                : pose.inverseTransformPoint(worldPoint);
        return local.div(scale);
    }

    /**
     * Rotates a world-space look vector into the structure-local frame. Look
     * vectors are directions, so the block scale is intentionally not applied.
     */
    public static Vector3d localLook(Transform3d pose, Vector3dc worldLook) {
        return pose == null
                ? new Vector3d(worldLook)
                : pose.inverseTransformDirection(worldLook);
    }

    /**
     * Minecraft yaw for a view vector: {@code 0} is south, {@code -90} east,
     * {@code 90} west and {@code 180} north.
     */
    public static float lookYaw(Vector3dc look) {
        return (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
    }

    /**
     * Minecraft pitch for a view vector: positive looks down.
     */
    public static float lookPitch(Vector3dc look) {
        final double horizontal = Math.hypot(look.x(), look.z());
        return (float) -Math.toDegrees(Math.atan2(look.y(), horizontal));
    }

    /**
     * Vanilla horizontal facing for a yaw angle.
     */
    public static Direction horizontalDirection(float yaw) {
        return Direction.fromYRot(yaw);
    }

    /**
     * Equivalent of {@link Direction#orderedByNearest(net.minecraft.world.entity.Entity)}
     * using a view vector instead of an entity. The result follows the same
     * branch structure as vanilla: the axes are ordered by the absolute look
     * component, while each direction keeps the component's sign.
     */
    public static Direction[] orderedLookingDirections(Vector3dc look) {
        final double x = look.x();
        final double y = look.y();
        final double z = look.z();
        final double ax = Math.abs(x);
        final double ay = Math.abs(y);
        final double az = Math.abs(z);
        final Direction xDir = x > 0.0 ? Direction.EAST : Direction.WEST;
        final Direction yDir = y > 0.0 ? Direction.UP : Direction.DOWN;
        final Direction zDir = z > 0.0 ? Direction.SOUTH : Direction.NORTH;

        if (ax > az) {
            if (ay > ax) {
                return new Direction[]{yDir, xDir, zDir};
            }
            if (az > ay) {
                return new Direction[]{xDir, zDir, yDir};
            }
            return new Direction[]{xDir, yDir, zDir};
        }
        if (ay > az) {
            return new Direction[]{yDir, zDir, xDir};
        }
        if (ax > ay) {
            return new Direction[]{zDir, xDir, yDir};
        }
        return new Direction[]{zDir, yDir, xDir};
    }

    /**
     * Builds the vanilla-shaped click used by a structure-local placement
     * context. Unlike {@link #blockHit(Vector3d, Direction)} this does not
     * floor world coordinates: the clicked voxel and the hit point are already
     * expressed in the structure's own grid.
     *
     * @param localPoint local click point in voxel metres
     * @param localFace  local outward face
     * @param clickedX   structure-local coordinates of the hit voxel
     * @param clickedY   structure-local coordinates of the hit voxel
     * @param clickedZ   structure-local coordinates of the hit voxel
     */
    public static BlockHitResult localBlockHit(Vector3dc localPoint, Direction localFace,
                                               int clickedX, int clickedY, int clickedZ) {
        final Direction face = localFace == null ? Direction.UP : localFace;
        return new BlockHitResult(
                new Vec3(localPoint.x(), localPoint.y(), localPoint.z()),
                face,
                new BlockPos(clickedX, clickedY, clickedZ),
                false);
    }

    private static double positiveScale(double value) {
        return value > 0.0 && Double.isFinite(value) ? value : 1.0;
    }

    /**
     * Whether a structure is aligned exactly with Minecraft's block grid, so a
     * block-state shape at a real {@link BlockPos} can be used for the vanilla
     * outline.
     */
    public static boolean isAxisAlignedGrid(Transform3d pose, double blockScale) {
        if (pose == null || Math.abs(blockScale - 1.0) > 1.0e-6) {
            return false;
        }
        final var rotation = pose.rotation();
        // Identity only: a 90-degree yaw is still grid-aligned, but a block
        // state's local shape would need the corresponding block rotation.
        if (Math.abs(rotation.w - 1.0) > 1.0e-6
                || Math.abs(rotation.x) > 1.0e-6
                || Math.abs(rotation.y) > 1.0e-6
                || Math.abs(rotation.z) > 1.0e-6) {
            return false;
        }
        // A fractional translation would put the voxel between grid cells.
        final Vector3d position = pose.position();
        return nearInteger(position.x) && nearInteger(position.y) && nearInteger(position.z);
    }

    private static boolean nearInteger(double value) {
        return Math.abs(value - Math.rint(value)) <= 1.0e-6;
    }

    /**
     * World-space block position of a structure voxel's lower corner when the
     * structure is exactly grid-aligned.
     */
    public static BlockPos gridBlockPos(Transform3d pose, int x, int y, int z, double blockScale) {
        final Vector3d world = pose.transformPoint(new Vector3d(
                x * blockScale,
                y * blockScale,
                z * blockScale
        ));
        return BlockPos.containing(world.x, world.y, world.z);
    }
}
