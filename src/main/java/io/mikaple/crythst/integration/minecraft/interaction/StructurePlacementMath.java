package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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
