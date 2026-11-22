package io.mikaple.crythst.integration.minecraft.gameevent;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pure coordinate/block-key helpers for the auxiliary→host game-event bridge.
 *
 * <p>The auxiliary void level stores a structure copy at an arbitrary region
 * base. An event position is first converted to structure-local metres and
 * then transformed by the current structure pose, exactly like entity and
 * sound projection. Keeping the maths outside the Minecraft bridge lets the
 * mapping be tested without a live level.</p>
 */
public final class GameEventCoordinates {
    private GameEventCoordinates() {
    }

    /**
     * Maps an auxiliary-level point to the host world.
     *
     * @param pose       structure pose; {@code null} uses identity
     * @param blockScale metres per voxel
     * @param originX    auxiliary coordinate of structure-local voxel {@code 0}
     * @param originY    auxiliary coordinate of structure-local voxel {@code 0}
     * @param originZ    auxiliary coordinate of structure-local voxel {@code 0}
     */
    public static Vector3d map(Transform3d pose, double blockScale,
                               int originX, int originY, int originZ,
                               double x, double y, double z) {
        final double scale = blockScale > 1.0e-9 ? blockScale : 1.0;
        final Vector3d local = new Vector3d(
                (x - originX) * scale,
                (y - originY) * scale,
                (z - originZ) * scale);
        return pose == null ? local : pose.transformPoint(local);
    }

    /** Rotates an auxiliary-level direction into the host world. */
    public static Vector3d mapDirection(Transform3d pose, Vector3dc direction) {
        final Vector3d copy = new Vector3d(direction);
        return pose == null ? copy : pose.transformDirection(copy);
    }

    /** Floors a fractional world point to its containing block position. */
    public static long blockKey(double x, double y, double z) {
        return packBlockPos(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Stable packed key for a block position, matching vanilla's
     * {@code BlockPos.asLong} layout. Pure so event dedup tests do not need a
     * Minecraft bootstrap.
     */
    public static long packBlockPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (long) (y & 0xFFF);
    }
}
