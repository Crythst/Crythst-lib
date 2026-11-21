package io.mikaple.crythst.integration.minecraft.player;

import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Builds the player's oriented collision box and computes minimum translation
 * vectors against terrain and physics structures.
 *
 * <p>Vanilla Minecraft always uses a Y-up axis-aligned bounding box. When a
 * player's gravity is non-default, Crythst instead treats the player as a box
 * whose local {@code +Y} axis follows the opposite of the gravity direction.
 * The box is centred on the <em>vanilla AABB centre</em>, with half extents
 * taken from the current player dimensions, so rotating the gravity frame
 * never moves the body sideways.</p>
 *
 * <p>This class is deliberately stateless and has no tick logic; the
 * {@link PlayerObbCollision} tick coordinator applies the push vectors it
 * computes.</p>
 */
public final class PlayerObb {
    /** Below this length a gravity vector is treated as degenerate. */
    private static final double EPSILON = 1.0e-9;

    private static final Vector3d VANILLA_UP = new Vector3d(0.0, 1.0, 0.0);

    private PlayerObb() {
    }

    /**
     * @return the gravity up direction for this player, or {@code null} when
     *         vanilla gravity is active and the OBB path must be skipped
     */
    public static Vector3d upOrNull(Player player) {
        // PlayerGravity treats a world/fixed direction indistinguishable from
        // straight down as vanilla, so the OBB path is skipped in that case.
        if (!PlayerGravity.hasDirectionalGravity(player)) {
            return null;
        }
        final Vector3d up = PlayerGravity.up(player);
        if (up.lengthSquared() < EPSILON * EPSILON) {
            return null;
        }
        return up.normalize();
    }

    /**
     * @return the gravity up direction, falling back to vanilla {@code +Y}
     *         when the player has default gravity
     */
    public static Vector3d up(Player player) {
        final Vector3d up = upOrNull(player);
        return up == null ? new Vector3d(VANILLA_UP) : up;
    }

    /**
     * Whether this player should use the OBB collision path at all.
     */
    public static boolean usesObbCollision(Player player) {
        return upOrNull(player) != null;
    }

    /**
     * Builds the player's world-space oriented box.
     *
     * <p>Minecraft's {@code position()} is the bottom-centre of the vanilla
     * <em>Y-up</em> bounding box, not the player's feet in the gravity frame.
     * The oriented box therefore stays anchored at the vanilla AABB centre
     * ({@code position + (0, height / 2, 0)}) and only its orientation changes.
     * This keeps the body volume in the same place for every gravity direction;
     * treating {@code position} as gravity feet would translate the box
     * sideways by {@code halfHeight * up} when gravity is rotated and clip the
     * player into terrain.</p>
     *
     * <p>Frame mapping:</p>
     * <ul>
     *   <li>vanilla AABB bottom-centre = {@code position()}</li>
     *   <li>vanilla AABB centre = {@code position() + (0, height / 2, 0)}</li>
     *   <li>eye = {@code position() + (0, eyeHeight, 0)} in vanilla space</li>
     *   <li>gravity feet = {@code obb.center() - halfHeight * up} (informational
     *       only; it generally does <em>not</em> equal {@code position()})</li>
     * </ul>
     */
    public static OrientedBox obb(Player player) {
        return obb(player, up(player));
    }

    public static OrientedBox obb(Player player, Vector3d up) {
        final Vec3 position = player.position();
        return box(position.x, position.y, position.z,
                player.getBbWidth(), player.getBbHeight(), up);
    }

    /**
     * Pure anchor/box constructor shared by the live player and unit tests.
     *
     * <p>The box centre is the centre of the vanilla Y-up AABB, so rotating
     * {@code up} never changes the centre's X/Z coordinates and never shifts
     * the box sideways relative to the body.</p>
     *
     * @param position  vanilla AABB bottom-centre
     * @param width     vanilla AABB width
     * @param height    vanilla AABB height (1.5 while crouching)
     * @param up        gravity up direction
     */
    public static OrientedBox box(double positionX, double positionY, double positionZ,
                                  double width, double height, Vector3d up) {
        final double halfWidth = Math.max(1.0e-4, width * 0.5);
        final double halfHeight = Math.max(1.0e-4, height * 0.5);

        final Vector3d axis = new Vector3d(up);
        if (axis.lengthSquared() < EPSILON * EPSILON) {
            axis.set(VANILLA_UP);
        } else {
            axis.normalize();
        }

        // Vanilla position is the Y-up AABB bottom-centre. Keep the OBB on the
        // same centre instead of interpreting position as gravity feet.
        final Vector3d center = new Vector3d(positionX, positionY + halfHeight, positionZ);

        final Quaterniond rotation = new Quaterniond().rotationTo(VANILLA_UP, axis);
        return new OrientedBox(
                center,
                new Vector3d(halfWidth, halfHeight, halfWidth),
                rotation
        );
    }

    /**
     * Minimum translation vector that moves {@code box} out of {@code other}.
     *
     * @return a world-space vector pointing out of the other box, or
     *         {@code null} when the boxes do not intersect
     */
    public static Vector3d penetration(OrientedBox box, OrientedBox other) {
        final Vector3d[] a = box.axes();
        final Vector3d[] b = other.axes();
        final Vector3d ah = box.halfExtents();
        final Vector3d bh = other.halfExtents();
        final Vector3d delta = new Vector3d(box.center()).sub(other.center());

        Vector3d bestAxis = null;
        double bestOverlap = Double.POSITIVE_INFINITY;

        // Face axes of the player box.
        for (int i = 0; i < 3; i++) {
            final double overlap = overlap(a[i], delta, a, ah, b, bh);
            if (overlap <= 0.0) {
                return null;
            }
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestAxis = a[i];
            }
        }

        // Face axes of the other box.
        for (int j = 0; j < 3; j++) {
            final double overlap = overlap(b[j], delta, a, ah, b, bh);
            if (overlap <= 0.0) {
                return null;
            }
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestAxis = b[j];
            }
        }

        // Edge-edge axes. The player box is usually only slightly rotated, so
        // these axes are still important for corners and tilted structures.
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final Vector3d cross = new Vector3d(a[i]).cross(b[j]);
                final double length = cross.length();
                if (length < EPSILON) {
                    continue;
                }
                cross.div(length);
                final double overlap = overlap(cross, delta, a, ah, b, bh);
                if (overlap <= 0.0) {
                    return null;
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    bestAxis = cross;
                }
            }
        }

        if (bestAxis == null) {
            return null;
        }

        // delta points from the other box toward the player box. Pushing along
        // its sign separates the player from the obstacle.
        final double sign = delta.dot(bestAxis) >= 0.0 ? 1.0 : -1.0;
        return new Vector3d(bestAxis).mul(sign * (bestOverlap + EPSILON));
    }

    /**
     * Computes the SAT overlap along {@code axis}. A non-positive result means
     * the axis separates the boxes.
     */
    private static double overlap(Vector3d axis, Vector3d delta,
                                  Vector3d[] a, Vector3d ah,
                                  Vector3d[] b, Vector3d bh) {
        final double playerRadius = Math.abs(axis.dot(a[0])) * ah.x
                + Math.abs(axis.dot(a[1])) * ah.y
                + Math.abs(axis.dot(a[2])) * ah.z;
        final double otherRadius = Math.abs(axis.dot(b[0])) * bh.x
                + Math.abs(axis.dot(b[1])) * bh.y
                + Math.abs(axis.dot(b[2])) * bh.z;
        return playerRadius + otherRadius - Math.abs(delta.dot(axis));
    }

    /**
     * Convenience overload for an axis-aligned obstacle.
     */
    public static Vector3d penetration(OrientedBox box, Aabb aabb) {
        return penetration(box, OrientedBox.fromAabb(aabb));
    }
}
