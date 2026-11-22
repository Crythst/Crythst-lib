package io.mikaple.crythst.integration.minecraft.boundary;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;

/**
 * Pure mapping between a structure's local voxel grid and the host world.
 *
 * <p>Crythst structures may be rotated and scaled. Redstone faces still need a
 * host {@link Direction}, so a world face is mapped to the locally dominant
 * axis. This is exact for axis-aligned structures and a documented best-effort
 * approximation for arbitrary rotations: a face whose normal lies at 45 degrees
 * still has to choose one of the six host neighbours.</p>
 *
 * <p>No auxiliary-level position is ever produced here. The only translation
 * exposed is host-world &lt;-&gt; structure-local voxel.</p>
 */
public final class BoundaryFaceMapping {
    private static final int MAX_SEARCH_RADIUS = 8;
    private static final double MAX_TOUCHING_DISTANCE = 9.0;

    private BoundaryFaceMapping() {
    }

    /**
     * Maps a host-world direction to the structure-local direction with the
     * largest dot product.
     */
    public static Direction localDirection(PhysicsStructure structure, Direction worldDirection) {
        if (structure == null || worldDirection == null) {
            return Direction.NORTH;
        }
        final Vector3d local = structure.pose()
                .inverseTransformDirection(new Vector3d(
                        worldDirection.getStepX(),
                        worldDirection.getStepY(),
                        worldDirection.getStepZ()));
        return nearestDirection(local.x, local.y, local.z);
    }

    /**
     * Maps a structure-local direction to the closest host-world direction.
     */
    public static Direction worldDirection(PhysicsStructure structure, Direction localDirection) {
        if (structure == null || localDirection == null) {
            return Direction.NORTH;
        }
        final Vector3d world = structure.pose()
                .transformDirection(new Vector3d(
                        localDirection.getStepX(),
                        localDirection.getStepY(),
                        localDirection.getStepZ()));
        return Direction.getApproximateNearest(world.x, world.y, world.z);
    }

    /** Host block containing the centre of a structure-local voxel. */
    public static BlockPos hostBlock(PhysicsStructure structure, int x, int y, int z) {
        final Vector3d center = structure.blockCenter(x, y, z);
        return BlockPos.containing(center.x, center.y, center.z);
    }

    /** Host block adjacent to a boundary face, just outside the volume. */
    public static BlockPos hostBlockOutside(PhysicsStructure structure, BoundaryFace face) {
        return hostBlock(structure, face.outsideX(), face.outsideY(), face.outsideZ());
    }

    /**
     * Maximum local-voxel distance at which a host block can be considered to
     * touch a structure voxel. A host block spans {@code 1 / blockScale} local
     * voxels, so a scaled-down structure has more than one candidate voxel per
     * host block.
     */
    public static double touchingDistance(double blockScale) {
        final double safeScale = Math.max(1.0e-3, blockScale);
        return Math.min(MAX_TOUCHING_DISTANCE, 0.5 / safeScale + 0.75);
    }

    /** Integer search radius for {@link #touchingDistance(double)}. */
    public static int searchRadius(double blockScale) {
        return Math.min(MAX_SEARCH_RADIUS,
                Math.max(1, (int) Math.ceil(touchingDistance(blockScale))));
    }

    private static Direction nearestDirection(double x, double y, double z) {
        final double ax = Math.abs(x);
        final double ay = Math.abs(y);
        final double az = Math.abs(z);
        if (ax >= ay && ax >= az) {
            return x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return y >= 0.0 ? Direction.UP : Direction.DOWN;
        }
        return z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }
}
