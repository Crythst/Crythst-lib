package io.mikaple.crythst.api.math;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Coordinate and quaternion conversions shared by Crythst structure APIs.
 *
 * <p><strong>Units and frames:</strong> all positions and distances are
 * world-space metres, using Minecraft's coordinate convention (east is
 * {@code +X}, up is {@code +Y}, south is {@code +Z}). A block at
 * {@code (x, y, z)} occupies the metre cube from {@code (x, y, z)} to
 * {@code (x + 1, y + 1, z + 1)}. Quaternions use the same right-handed rotation
 * convention as JOML and Minecraft.</p>
 *
 * <p><strong>Side:</strong> every method here is pure and performs no level,
 * client or native access, so the class is safe on both the dedicated server
 * and the client. Methods without a {@code dest} argument allocate a new
 * vector; methods with a {@code dest} argument overwrite and return it.</p>
 *
 * <p>The constants {@link #ZERO} and {@link #QUAT_IDENTITY} are exposed through
 * their read-only JOML interfaces. Callers must not cast them back to mutable
 * implementations and modify them; use {@code new Vector3d(ZERO)} or
 * {@code new Quaterniond(QUAT_IDENTITY)} when a mutable copy is needed. No
 * method in this class returns either constant directly.</p>
 */
public final class StructureMath {
    /**
     * The origin, {@code (0, 0, 0)}, in metres.
     *
     * <p>Treat this value as immutable. It is exposed as {@link Vector3dc} so
     * accidental mutation through the normal API is not possible.</p>
     */
    public static final Vector3dc ZERO = new Vector3d();

    /**
     * The identity rotation, {@code (x=0, y=0, z=0, w=1)}.
     *
     * <p>Treat this value as immutable. It is exposed as
     * {@link Quaterniondc} so accidental mutation through the normal API is not
     * possible.</p>
     */
    public static final Quaterniondc QUAT_IDENTITY = new Quaterniond();

    private StructureMath() {
    }

    /**
     * Returns the world-space centre of a block, in metres.
     *
     * <p>The result is {@code (x + 0.5, y + 0.5, z + 0.5)} and is a new vector.
     * This method performs no level access and is safe on either side.</p>
     *
     * @param pos block position; must not be {@code null}
     * @return a new vector containing the block centre
     * @throws NullPointerException when {@code pos} is {@code null}
     */
    public static Vector3d atCenterOf(@NotNull BlockPos pos) {
        return atCenterOf(pos, new Vector3d());
    }

    /**
     * Writes the world-space centre of a block into {@code dest}.
     *
     * <p>See {@link #atCenterOf(BlockPos)} for the coordinate convention.</p>
     *
     * @param pos  block position; must not be {@code null}
     * @param dest destination, overwritten with the centre in metres
     * @return {@code dest}
     * @throws NullPointerException when {@code pos} or {@code dest} is
     *                              {@code null}
     */
    public static Vector3d atCenterOf(@NotNull BlockPos pos, @NotNull Vector3d dest) {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        if (dest == null) {
            throw new NullPointerException("dest");
        }
        return dest.set(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * Returns the world-space lower corner of a block, in metres.
     *
     * <p>The result is exactly {@code (x, y, z)} and is a new vector. This is
     * the minimum corner of the block's one-metre cube and is useful for
     * structure-local voxel coordinates. This method performs no level access
     * and is safe on either side.</p>
     *
     * @param pos block position; must not be {@code null}
     * @return a new vector containing the block's lower corner
     * @throws NullPointerException when {@code pos} is {@code null}
     */
    public static Vector3d atLowerCornerOf(@NotNull BlockPos pos) {
        return atLowerCornerOf(pos, new Vector3d());
    }

    /**
     * Writes the world-space lower corner of a block into {@code dest}.
     *
     * <p>See {@link #atLowerCornerOf(BlockPos)} for the coordinate
     * convention.</p>
     *
     * @param pos  block position; must not be {@code null}
     * @param dest destination, overwritten with the lower corner in metres
     * @return {@code dest}
     * @throws NullPointerException when {@code pos} or {@code dest} is
     *                              {@code null}
     */
    public static Vector3d atLowerCornerOf(@NotNull BlockPos pos, @NotNull Vector3d dest) {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        if (dest == null) {
            throw new NullPointerException("dest");
        }
        return dest.set(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Converts a JOML vector to Minecraft's {@link Vec3}.
     *
     * <p>Coordinates are copied verbatim, so the source and destination use the
     * same units and frame. The returned vector is a new, independent value.</p>
     *
     * @param value vector to convert; must not be {@code null}
     * @return a new Minecraft vector with the same coordinates
     * @throws NullPointerException when {@code value} is {@code null}
     */
    public static Vec3 toMojang(@NotNull Vector3dc value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return new Vec3(value.x(), value.y(), value.z());
    }

    /**
     * Converts Minecraft's {@link Vec3} to a new JOML vector.
     *
     * <p>Coordinates are copied verbatim, so the source and destination use the
     * same units and frame.</p>
     *
     * @param value vector to convert; must not be {@code null}
     * @return a new JOML vector with the same coordinates
     * @throws NullPointerException when {@code value} is {@code null}
     */
    public static Vector3d toJoml(@NotNull Vec3 value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return new Vector3d(value.x, value.y, value.z);
    }

    /**
     * Writes a JOML vector, or any {@link Vector3dc}, into {@code dest}.
     *
     * <p>This overload also accepts the source and destination being the same
     * object. Coordinates and units are preserved.</p>
     *
     * @param value vector to convert; must not be {@code null}
     * @param dest  destination, overwritten with {@code value}
     * @return {@code dest}
     * @throws NullPointerException when {@code value} or {@code dest} is
     *                              {@code null}
     */
    public static Vector3d toJoml(@NotNull Vector3dc value, @NotNull Vector3d dest) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (dest == null) {
            throw new NullPointerException("dest");
        }
        return dest.set(value);
    }

    /**
     * Converts Minecraft's float quaternion to double precision.
     *
     * <p>Components are copied without normalisation, so an already-normalised
     * rotation stays normalised. Minecraft stores rotations as
     * {@link Quaternionf} even though the class lives in JOML.</p>
     *
     * @param value quaternion to convert; must not be {@code null}
     * @return a new double-precision quaternion
     * @throws NullPointerException when {@code value} is {@code null}
     */
    public static Quaterniond toJoml(@NotNull Quaternionf value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return new Quaterniond(value.x(), value.y(), value.z(), value.w());
    }

    /**
     * Converts a double-precision quaternion to Minecraft's float quaternion.
     *
     * <p>Components are copied without normalisation. Round-tripping through
     * this method and {@link #toJoml(Quaternionf)} loses precision beyond that
     * of a {@code float}.</p>
     *
     * @param value quaternion to convert; must not be {@code null}
     * @return a new float-precision quaternion
     * @throws NullPointerException when {@code value} is {@code null}
     */
    public static Quaternionf toMojang(@NotNull Quaterniondc value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return new Quaternionf((float) value.x(), (float) value.y(),
                (float) value.z(), (float) value.w());
    }
}
