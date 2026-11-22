package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Vector3d;

/**
 * Pure world-space mapping for structure break/use particles.
 *
 * <p>The structure pose maps structure-local <em>metres</em> to world space.
 * A voxel {@code (x,y,z)} with a block scale {@code s} spans
 * {@code [x*s, (x+1)*s]} on each axis, so a local fraction {@code (fx,fy,fz)}
 * inside that voxel becomes
 * {@code pose.transformPoint((x+fx)*s, (y+fy)*s, (z+fz)*s)}. This keeps
 * particles attached to rotated, translated and scaled structures instead of
 * snapping them to a {@code BlockPos} centre.</p>
 */
public final class StructureParticleMath {
    /** Fraction of the local voxel size used as debris speed at scale 1. */
    public static final double DEBRIS_SPEED_FRACTION = 0.12;

    private StructureParticleMath() {
    }

    /**
     * Exact world point for a fraction inside a structure voxel.
     *
     * @param pose       structure pose (not the centre-of-mass body pose)
     * @param voxelX     structure-local voxel X
     * @param voxelY     structure-local voxel Y
     * @param voxelZ     structure-local voxel Z
     * @param blockScale metres per voxel
     * @param localX     local fraction in {@code [0,1]} on X
     * @param localY     local fraction in {@code [0,1]} on Y
     * @param localZ     local fraction in {@code [0,1]} on Z
     * @return a new world-space point
     */
    public static Vector3d worldPosition(Transform3d pose, int voxelX, int voxelY, int voxelZ,
                                         double blockScale,
                                         double localX, double localY, double localZ) {
        return pose.transformPoint(new Vector3d(
                (voxelX + localX) * blockScale,
                (voxelY + localY) * blockScale,
                (voxelZ + localZ) * blockScale));
    }

    /**
     * World-space debris velocity for a local fraction inside a voxel.
     *
     * <p>The local direction is {@code (fraction - 0.5)} so samples near a
     * face fly outwards. Rotation is applied by the pose and the magnitude
     * scales with {@code blockScale}, matching the larger visual size of a
     * scaled voxel.</p>
     */
    public static Vector3d debrisVelocity(Transform3d pose,
                                           double localX, double localY, double localZ,
                                           double blockScale) {
        final Vector3d local = new Vector3d(
                localX - 0.5, localY - 0.5, localZ - 0.5)
                .mul(blockScale * DEBRIS_SPEED_FRACTION);
        return pose.transformDirection(local);
    }

    /** Exact world centre of a structure voxel. */
    public static Vector3d voxelCenter(Transform3d pose, int voxelX, int voxelY, int voxelZ,
                                       double blockScale) {
        return worldPosition(pose, voxelX, voxelY, voxelZ, blockScale, 0.5, 0.5, 0.5);
    }

    /** Clamps a non-positive/non-finite scale to a normal one-block voxel. */
    public static double safeScale(double blockScale) {
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            return 1.0;
        }
        return blockScale;
    }
}
