package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player gravity direction.
 *
 * <p>By default a player uses vanilla gravity (straight down). A feature mod or
 * the {@code /crythst playergravity} command can install a fixed direction or a
 * "look" mode where the player's view vector <em>at the moment the mode is
 * enabled</em> becomes "down". The sampled direction is frozen so the camera
 * frame does not chase the mouse; the mixin in
 * {@code io.mikaple.crythst.mixin.LivingEntityTravelMixin} replaces the vanilla
 * downward gravity application for that player.</p>
 *
 * <p>{@link #up(Player)}, {@link #viewRotation(Player)} and
 * {@link #lookDirection(Player)} expose the same frame to the client camera
 * ({@code io.mikaple.crythst.mixin.client.CameraGravityMixin}) and to the
 * player collision box.</p>
 *
 * <p>In a singleplayer world the integrated server and client share this map, so
 * client prediction follows the same direction. Dedicated-server sync is not
 * implemented yet.</p>
 */
public final class PlayerGravity {
    private enum Mode {
        FIXED,
        LOOK
    }

    private record Entry(Mode mode, Vector3d direction) {
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Vector3d> WORLD_DIRECTIONS = new ConcurrentHashMap<>();

    private PlayerGravity() {
    }

    /**
     * Makes the dimension's players follow the physics world gravity direction
     * unless they have a per-player override. Called by `/crythst gravity`.
     */
    public static void setWorldDirection(ResourceKey<Level> dimension, Vector3dc downDirection) {
        if (downDirection == null || downDirection.lengthSquared() < 1.0e-12) {
            WORLD_DIRECTIONS.remove(dimension);
            return;
        }
        WORLD_DIRECTIONS.put(dimension, new Vector3d(downDirection).normalize());
    }

    public static void setFixed(Player player, Vector3dc downDirection) {
        final Vector3d direction = new Vector3d(downDirection);
        if (direction.lengthSquared() < 1.0e-12) {
            throw new IllegalArgumentException("Gravity direction must not be zero");
        }
        direction.normalize();
        ENTRIES.put(player.getUUID(), new Entry(Mode.FIXED, direction));
    }

    /**
     * Samples the player's current view direction and uses it as gravity
     * "down".
     *
     * <p>The direction is captured once, at the moment this method is called;
     * it does not keep following the mouse. This is deliberate. A camera frame
     * derived from the instantaneous look vector is a feedback loop: with
     * {@code Q = rotationTo(worldUp, -look)}, the rendered forward
     * {@code Q * look} becomes independent of yaw at zero pitch, so the world
     * only rolls and never turns. Freezing "down" makes {@code Q} stable and
     * every later mouse movement a normal rotation inside the gravity frame.
     * Call this method again to re-sample a new direction.</p>
     *
     * <p>Looking straight down samples vanilla down, so
     * {@link #hasDirectionalGravity(Player)} stays {@code false} and nothing
     * rotates. The useful case is looking along a wall or the horizon.</p>
     */
    public static void setLook(Player player) {
        final Vec3 look = player.getLookAngle();
        final Vector3d direction = new Vector3d(look.x, look.y, look.z);
        if (direction.lengthSquared() < 1.0e-12) {
            direction.set(0.0, -1.0, 0.0);
        } else {
            direction.normalize();
        }
        ENTRIES.put(player.getUUID(), new Entry(Mode.LOOK, direction));
        Crythst.LOGGER.info("Crythst player gravity look snapshot: down=({}, {}, {})",
                String.format("%.3f", direction.x), String.format("%.3f", direction.y),
                String.format("%.3f", direction.z));
    }

    public static void reset(Player player) {
        ENTRIES.remove(player.getUUID());
    }

    public static void clearAll() {
        ENTRIES.clear();
        WORLD_DIRECTIONS.clear();
    }

    public static boolean hasOverride(Player player) {
        return ENTRIES.containsKey(player.getUUID());
    }

    /**
     * @return downward unit vector, or {@code null} when vanilla gravity applies
     */
    public static Vector3d direction(Player player) {
        final Entry entry = ENTRIES.get(player.getUUID());
        if (entry == null) {
            final Vector3d world = WORLD_DIRECTIONS.get(player.level().dimension());
            return world == null ? null : new Vector3d(world);
        }
        if (entry.mode == Mode.LOOK) {
            // Normally populated by setLook. Keep a defensive fallback for an
            // entry created before the snapshot field existed.
            if (entry.direction != null) {
                return new Vector3d(entry.direction);
            }
            final Vec3 look = player.getLookAngle();
            final Vector3d direction = new Vector3d(look.x, look.y, look.z);
            if (direction.lengthSquared() < 1.0e-12) {
                return new Vector3d(0.0, -1.0, 0.0);
            }
            return direction.normalize();
        }
        return new Vector3d(entry.direction);
    }

    /**
     * Whether {@code player} currently uses a real directional gravity field.
     *
     * <p>This is {@code false} for the vanilla default (including a world or
     * fixed direction that is numerically indistinguishable from straight
     * down). All gravity-aware client code should use this guard so the
     * default path stays bit-for-bit identical to vanilla.</p>
     */
    public static boolean hasDirectionalGravity(Player player) {
        if (player == null) {
            return false;
        }
        final Vector3d direction = direction(player);
        if (direction == null) {
            return false;
        }
        // 1e-8 squared distance ~= 1e-4 rad from straight down.
        return direction.distanceSquared(0.0, -1.0, 0.0) > 1.0e-8;
    }

    /**
     * Up direction of the player's gravity frame: {@code -direction(player)}.
     *
     * <p>The collision box and the camera should use this as "up". It is
     * always a unit vector and falls back to world up {@code (0,1,0)} when
     * vanilla gravity applies.</p>
     */
    public static Vector3d up(Player player) {
        final Vector3d direction = player == null ? null : direction(player);
        if (direction == null) {
            return new Vector3d(0.0, 1.0, 0.0);
        }
        return direction.negate();
    }

    /**
     * Rotation that maps world up {@code (0,1,0)} onto the player's gravity
     * up, i.e. the rotation taking vanilla space into the player's gravity
     * frame.
     *
     * <p>The returned quaternion is the identity when no directional gravity
     * applies (or when gravity points straight down). Client code composes it
     * <em>on the left</em> of the vanilla camera orientation so that the
     * camera's local up becomes {@link #up(Player)} while its yaw/pitch remain
     * the vanilla frame's values.</p>
     */
    public static Quaterniond viewRotation(Player player) {
        if (!hasDirectionalGravity(player)) {
            return new Quaterniond();
        }
        return rotationForUp(up(player));
    }

    /**
     * Pure frame helper: shortest-arc rotation that maps world up
     * {@code (0,1,0)} onto {@code up}.
     *
     * <p>This is the {@code Q} used by the camera, avatar and debug hitbox.
     * It is exposed separately from {@link #viewRotation(Player)} so the
     * composition can be tested without a live {@link Player}. A degenerate or
     * zero {@code up} yields the identity.</p>
     */
    public static Quaterniond rotationForUp(Vector3dc up) {
        if (up == null || up.lengthSquared() < 1.0e-20) {
            return new Quaterniond();
        }
        final Vector3d normalized = new Vector3d(up).normalize();
        return new Quaterniond().rotationTo(0.0, 1.0, 0.0,
                normalized.x, normalized.y, normalized.z);
    }

    /**
     * Pure frame helper: rotates a camera position offset from {@code eye}
     * into the gravity frame.
     *
     * <p>Vanilla computes the third-person camera position in the world-up
     * frame as {@code eye + offset}. Composing the whole view by {@code Q}
     * rotates that offset too, so the new position is
     * {@code eye + Q * offset}. A zero offset (first person) is unchanged, and
     * applying the conjugate restores the original position.</p>
     */
    public static Vector3d rotateOffset(Quaterniondc rotation, Vector3dc eye,
                                        Vector3dc cameraPosition) {
        return new Vector3d(cameraPosition).sub(eye).rotate(rotation).add(eye);
    }

    /**
     * The player's vanilla look vector rotated into the gravity frame.
     *
     * <p>This is the direction that matches what the gravity-rotated camera
     * shows, and therefore the vector interaction raycasts should use when
     * directional gravity is active. Returns the plain vanilla look vector
     * (a fresh copy) when no directional gravity applies.</p>
     */
    public static Vector3d lookDirection(Player player) {
        final Vec3 look = player.getLookAngle();
        final Vector3d result = new Vector3d(look.x, look.y, look.z);
        if (!hasDirectionalGravity(player)) {
            return result;
        }
        return viewRotation(player).transform(result);
    }

    public static String describe(Player player) {
        final Entry entry = ENTRIES.get(player.getUUID());
        if (entry == null) {
            final Vector3d world = WORLD_DIRECTIONS.get(player.level().dimension());
            return world == null ? "vanilla (0,-1,0)"
                    : String.format("world (%.3f, %.3f, %.3f)", world.x, world.y, world.z);
        }
        if (entry.direction == null) {
            return "look direction (not sampled)";
        }
        final String prefix = entry.mode == Mode.LOOK ? "look snapshot " : "";
        return prefix + String.format("(%.3f, %.3f, %.3f)",
                entry.direction.x, entry.direction.y, entry.direction.z);
    }
}
