package io.mikaple.crythst.integration.minecraft.player;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-move terrain correction for players with non-default gravity.
 *
 * <p>Vanilla movement always predicts a Y-up AABB. When gravity is rotated the
 * actual body volume is an OBB whose local {@code +Y} follows gravity, so the
 * vanilla box can leave part of the body inside terrain. This coordinator runs
 * at the end of the player tick, builds that OBB around the vanilla AABB centre
 * and moves the player out along the minimum translation vector.</p>
 *
 * <p>The pass is deliberately terrain-only. Physics structures already take
 * part in vanilla {@code Entity.move} through {@code EntityStructureCollision},
 * and resolving them again with this post-tick box would double-resolve floors
 * and make crouching feel sticky.</p>
 *
 * <p>Both sides run the same code. No teleport packet is sent for a contact:
 * small corrections are applied locally on the client and server, which keeps
 * the client's prediction and the server's authoritative position in sync
 * without wiping delta movement every tick.</p>
 */
public final class PlayerObbCollision {
    /** More than a few contacts can be chained (corner + floor + wall). */
    private static final int MAX_ITERATIONS = 3;

    /** Pushes below this length are ignored to avoid jitter. */
    private static final double MIN_PUSH = 1.0e-4;

    /** Safety cap so a corrupted collider can never fling the player. */
    private static final double MAX_PUSH_PER_ITERATION = 1.5;

    /** Broad-phase padding around the player OBB. */
    private static final double BROADPHASE_MARGIN = 0.25;

    /** A correction deeper than this is applied fully instead of softly. */
    private static final double DEEP_PUSH = 0.25;

    /** Contact normals within this angle of gravity-up are support contacts. */
    private static final double SUPPORT_DOT = 0.55;

    /** Base depth range in which a support exit wins over a shallower wall. */
    private static final double SUPPORT_LIMIT = 0.6;

    /** Support depth never grows beyond this, even for a long fall. */
    private static final double MAX_SUPPORT_LIMIT = 2.0;

    /** Minimum travelled distance worth a swept check, in metres. */
    private static final double SWEEP_MIN_DISTANCE = 0.3;

    /** Sample count used between the previous and current player position. */
    private static final int SWEEP_SAMPLES = 4;

    /** Largest snap-back a swept check may apply. */
    private static final double MAX_SWEEP_CORRECTION = 2.0;

    /** Debug output is limited to one line per player per second. */
    private static final long DEBUG_INTERVAL_NANOS = 1_000_000_000L;
    private static final Map<UUID, Long> LAST_DEBUG_NANOS = new ConcurrentHashMap<>();

    private PlayerObbCollision() {
    }

    /**
     * Called once at the end of the player tick on both sides.
     */
    public static void tick(Player player) {
        if (player == null) {
            return;
        }
        final CrythstConfig config = MinecraftPhysics.config();
        if (!config.enabled || !config.playerObbCollision) {
            return;
        }
        if (player.noPhysics
                || player.isRemoved()
                || player.isSpectator()
                || player.isPassenger()
                || player.isSleeping()
                || player.isDeadOrDying()) {
            return;
        }

        // Null means vanilla gravity: leave the vanilla AABB path untouched.
        final Vector3d up = PlayerObb.upOrNull(player);
        if (up == null) {
            return;
        }

        final double strength = Math.clamp(config.playerObbCollisionStrength, 0.0, 1.0);
        if (strength <= 0.0) {
            return;
        }

        final Vector3d total = new Vector3d();
        final List<Vector3d> contactNormals = new ArrayList<>(4);

        // A fast move can carry the rotated OBB completely past a thin ledge
        // between ticks: the old and the new box are both clear, so a
        // post-move penetration test reports nothing. Sweep the tick's motion
        // and cut it at the first contact instead.
        final OrientedBox currentBox = PlayerObb.obb(player, up);
        if (findPushAt(player, currentBox, up) == null) {
            if (!sweepToFirstContact(player, up, total, contactNormals)) {
                // The swept SAT test skips obstacles the box already overlaps
                // (recovery, not tunnelling), so keep the coarse sample sweep
                // as a fallback for grazing contacts it cannot resolve.
                final Vector3d swept = sweptCorrection(player, currentBox, up);
                if (swept != null) {
                    player.setPos(player.getX() + swept.x,
                            player.getY() + swept.y,
                            player.getZ() + swept.z);
                    total.add(swept);
                    addContact(contactNormals, new Vector3d(swept).normalize());
                }
            }
        }

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            final Vector3d push = findPush(player, up);
            if (push == null || push.lengthSquared() < MIN_PUSH * MIN_PUSH) {
                break;
            }

            // Use the configured softness for shallow contacts, but separate a
            // genuinely embedded player in one pass. Capping still happens so a
            // corrupted collider cannot fling the body.
            final double length = push.length();
            final double factor = length > DEEP_PUSH ? 1.0 : strength;
            final Vector3d delta = new Vector3d(push).mul(factor);
            final double deltaLength = delta.length();
            if (deltaLength > MAX_PUSH_PER_ITERATION) {
                delta.mul(MAX_PUSH_PER_ITERATION / deltaLength);
            }
            player.setPos(
                    player.getX() + delta.x,
                    player.getY() + delta.y,
                    player.getZ() + delta.z
            );
            total.add(delta);
            addContact(contactNormals, new Vector3d(push).normalize());
        }

        if (total.lengthSquared() < MIN_PUSH * MIN_PUSH) {
            return;
        }

        // Remove only the components that point into the surfaces. Projecting
        // against every contact keeps tangential walking/sneaking velocity.
        final Vec3 motion = player.getDeltaMovement();
        final Vector3d filtered = new Vector3d(motion.x, motion.y, motion.z);
        for (Vector3d normal : contactNormals) {
            final double intoSurface = filtered.dot(normal);
            if (intoSurface < 0.0) {
                filtered.fma(-intoSurface, normal);
            }
        }
        if (filtered.x != motion.x || filtered.y != motion.y || filtered.z != motion.z) {
            player.setDeltaMovement(filtered.x, filtered.y, filtered.z);
        }

        // No ServerPlayer.connection.teleport: a contact correction must not
        // erase the client's predicted movement. The client runs this same
        // terrain pass, so both sides converge without a packet.
        logDebug(player, up, PlayerObb.obb(player, up), total);
    }

    /**
     * Finds the shallowest terrain penetration for the rotated player box.
     *
     * @return a world-space push vector, or {@code null} when free
     */
    private static Vector3d findPush(Player player, Vector3d up) {
        return findPushAt(player, PlayerObb.obb(player, up), up);
    }

    private static Vector3d findPushAt(Player player, OrientedBox box, Vector3d up) {
        final Aabb broad = box.toAabb().expand(BROADPHASE_MARGIN);
        final Level level = player.level();

        final net.minecraft.world.phys.AABB query = new net.minecraft.world.phys.AABB(
                broad.min().x, broad.min().y, broad.min().z,
                broad.max().x, broad.max().y, broad.max().z
        );

        final List<Vector3d> pushes = new ArrayList<>(4);
        for (VoxelShape shape : level.getBlockCollisions(player, query)) {
            if (shape.isEmpty()) {
                continue;
            }
            for (net.minecraft.world.phys.AABB raw : shape.toAabbs()) {
                final Aabb obstacle = new Aabb(
                        new Vector3d(raw.minX, raw.minY, raw.minZ),
                        new Vector3d(raw.maxX, raw.maxY, raw.maxZ)
                );
                final Vector3d push = PlayerObb.penetration(box, obstacle);
                if (push != null && push.lengthSquared() >= MIN_PUSH * MIN_PUSH) {
                    pushes.add(push);
                }
            }
        }

        final Vec3 motion = player.getDeltaMovement();
        final double verticalSpeed = motion.x * up.x + motion.y * up.y + motion.z * up.z;
        final boolean preferSupport = player.onGround() || verticalSpeed <= 0.0;
        final double supportLimit = Math.min(MAX_SUPPORT_LIMIT,
                SUPPORT_LIMIT + Math.max(0.0, -verticalSpeed));
        return selectPush(pushes, up, preferSupport, supportLimit);
    }

    /**
     * Chooses which penetration to resolve. A support exit (normal close to
     * gravity-up) wins over a shallower sideways push while the player is
     * grounded or descending, mirroring the movement structure solver. Without
     * this, standing on a floor with rotated gravity can be treated as a wall
     * contact and shove the player sideways.
     *
     * <p>Package-visible for pure unit tests.</p>
     */
    static Vector3d selectPush(List<Vector3d> pushes, Vector3d up,
                               boolean preferSupport, double supportLimit) {
        Vector3d best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        Vector3d support = null;
        double supportLength = Double.POSITIVE_INFINITY;

        for (Vector3d push : pushes) {
            if (push == null) {
                continue;
            }
            final double lengthSquared = push.lengthSquared();
            if (lengthSquared < MIN_PUSH * MIN_PUSH) {
                continue;
            }
            final double length = Math.sqrt(lengthSquared);
            final double alignment = push.dot(up) / length;
            if (preferSupport && alignment > SUPPORT_DOT && length <= supportLimit
                    && length < supportLength) {
                support = new Vector3d(push);
                supportLength = length;
            }
            if (length < bestLength) {
                best = new Vector3d(push);
                bestLength = length;
            }
        }
        return support != null ? support : best;
    }

    /**
     * Cuts this tick's motion at the first terrain contact.
     *
     * <p>Uses {@link PlayerObbSweep} so a motion that fully clears a thin
     * obstacle within one tick is still stopped by it, which a post-move
     * penetration test cannot detect.</p>
     *
     * @return {@code true} when an impact was found and applied
     */
    private static boolean sweepToFirstContact(Player player, Vector3d up,
                                               Vector3d total, List<Vector3d> contactNormals) {
        final Vec3 previous = player.oldPosition();
        final Vec3 current = player.position();
        final Vector3d motion = new Vector3d(
                current.x - previous.x,
                current.y - previous.y,
                current.z - previous.z
        );
        if (motion.lengthSquared() < SWEEP_MIN_DISTANCE * SWEEP_MIN_DISTANCE) {
            return false;
        }

        final OrientedBox from = PlayerObb.box(previous.x, previous.y, previous.z,
                player.getBbWidth(), player.getBbHeight(), up);
        final OrientedBox to = PlayerObb.box(current.x, current.y, current.z,
                player.getBbWidth(), player.getBbHeight(), up);
        final Aabb sweptBounds = from.toAabb().union(to.toAabb());
        final List<Aabb> obstacles = terrainAround(player, sweptBounds.expand(BROADPHASE_MARGIN));

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(from, motion, obstacles);
        if (impact == null) {
            return false;
        }

        final Vector3d target = PlayerObbSweep.positionBeforeImpact(
                new Vector3d(previous.x, previous.y, previous.z), motion, impact);
        final Vector3d delta = new Vector3d(target).sub(current.x, current.y, current.z);
        if (delta.length() > MAX_SWEEP_CORRECTION) {
            return false;
        }

        player.setPos(target.x, target.y, target.z);
        total.add(delta);
        addContact(contactNormals, impact.normal());
        return true;
    }

    /**
     * Terrain boxes overlapping {@code broad}, in Crythst's box type.
     */
    private static List<Aabb> terrainAround(Player player, Aabb broad) {
        final net.minecraft.world.phys.AABB query = new net.minecraft.world.phys.AABB(
                broad.min().x, broad.min().y, broad.min().z,
                broad.max().x, broad.max().y, broad.max().z
        );
        final List<Aabb> obstacles = new ArrayList<>(8);
        for (VoxelShape shape : player.level().getBlockCollisions(player, query)) {
            if (shape.isEmpty()) {
                continue;
            }
            for (net.minecraft.world.phys.AABB raw : shape.toAabbs()) {
                obstacles.add(new Aabb(
                        new Vector3d(raw.minX, raw.minY, raw.minZ),
                        new Vector3d(raw.maxX, raw.maxY, raw.maxZ)
                ));
            }
        }
        return obstacles;
    }

    /**
     * Sweeps the rotated OBB between the previous and current tick positions.
     * Only used when the current box is already clear, i.e. the Y-up vanilla
     * sweep let the rotated body pass through terrain.
     */
    private static Vector3d sweptCorrection(Player player, OrientedBox currentBox, Vector3d up) {
        final Vec3 previous = player.oldPosition();
        final Vec3 current = player.position();
        final double dx = current.x - previous.x;
        final double dy = current.y - previous.y;
        final double dz = current.z - previous.z;
        if (dx * dx + dy * dy + dz * dz < SWEEP_MIN_DISTANCE * SWEEP_MIN_DISTANCE) {
            return null;
        }

        final double width = player.getBbWidth();
        final double height = player.getBbHeight();

        // If the old position was already embedded, this tick is a recovery,
        // not a tunnelling event. Do not drag the player further backwards.
        final OrientedBox previousBox = PlayerObb.box(
                previous.x, previous.y, previous.z, width, height, up);
        if (findPushAt(player, previousBox, up) != null) {
            return null;
        }

        for (int sample = 1; sample < SWEEP_SAMPLES; sample++) {
            final double t = (double) sample / SWEEP_SAMPLES;
            final OrientedBox sampleBox = PlayerObb.box(
                    previous.x + dx * t,
                    previous.y + dy * t,
                    previous.z + dz * t,
                    width, height, up);
            final Vector3d hit = findPushAt(player, sampleBox, up);
            if (hit == null) {
                continue;
            }

            // Move from the current centre to the first sampled contact plus
            // its separation vector. That is the earliest position vanilla's
            // Y-up sweep should have stopped at.
            final Vector3d target = sampleBox.center().add(hit);
            final Vector3d correction = target.sub(currentBox.center());
            if (correction.lengthSquared() > MAX_SWEEP_CORRECTION * MAX_SWEEP_CORRECTION) {
                return null;
            }
            return correction;
        }
        return null;
    }

    private static void addContact(List<Vector3d> contacts, Vector3d normal) {
        for (Vector3d existing : contacts) {
            if (existing.dot(normal) > 0.9999) {
                return;
            }
        }
        contacts.add(normal);
    }

    private static void logDebug(Player player, Vector3d up, OrientedBox box, Vector3d correction) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (config == null || !config.debug) {
            return;
        }
        final long now = System.nanoTime();
        final UUID id = player.getUUID();
        final Long previous = LAST_DEBUG_NANOS.get(id);
        if (previous != null && now - previous < DEBUG_INTERVAL_NANOS) {
            return;
        }
        LAST_DEBUG_NANOS.put(id, now);

        final Vec3 position = player.position();
        final Aabb aabb = new Aabb(
                new Vector3d(player.getBoundingBox().minX, player.getBoundingBox().minY,
                        player.getBoundingBox().minZ),
                new Vector3d(player.getBoundingBox().maxX, player.getBoundingBox().maxY,
                        player.getBoundingBox().maxZ)
        );
        final Vector3d anchor = new Vector3d(
                position.x, position.y + player.getBbHeight() * 0.5, position.z);
        Crythst.LOGGER.debug(
                "playerObb side={} up=({},{},{}) position=({},{},{}) anchor=({},{},{}) aabb=[{},{}] obbCenter=({},{},{}) correction=({},{},{}) length={}",
                player.level().isClientSide() ? "client" : "server",
                fmt(up.x), fmt(up.y), fmt(up.z),
                fmt(position.x), fmt(position.y), fmt(position.z),
                fmt(anchor.x), fmt(anchor.y), fmt(anchor.z),
                aabb.min(), aabb.max(),
                fmt(box.center().x), fmt(box.center().y), fmt(box.center().z),
                fmt(correction.x), fmt(correction.y), fmt(correction.z),
                fmt(correction.length()));
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /**
     * Exposed for diagnostics/tests: the current player OBB, or {@code null}
     * when vanilla gravity is active.
     */
    public static OrientedBox currentBox(Player player) {
        final Vector3d up = PlayerObb.upOrNull(player);
        return up == null ? null : PlayerObb.obb(player, up);
    }

    /**
     * Diagnostic snapshot for a debug command or log. The correction is the
     * current one-iteration push, not a position delta already applied.
     */
    public static DebugInfo debug(Player player) {
        final Vector3d up = PlayerObb.upOrNull(player);
        if (up == null) {
            return null;
        }
        final OrientedBox box = PlayerObb.obb(player, up);
        final Vector3d push = findPushAt(player, box, up);
        final Vec3 position = player.position();
        return new DebugInfo(
                up,
                new Vector3d(position.x,
                        position.y + player.getBbHeight() * 0.5,
                        position.z),
                new Aabb(
                        new Vector3d(player.getBoundingBox().minX, player.getBoundingBox().minY,
                                player.getBoundingBox().minZ),
                        new Vector3d(player.getBoundingBox().maxX, player.getBoundingBox().maxY,
                                player.getBoundingBox().maxZ)
                ),
                box.center(),
                push == null ? null : new Vector3d(push).normalize(),
                push == null ? 0.0 : push.length(),
                push == null ? new Vector3d() : push
        );
    }

    /** Debug view of the rotated terrain correction. */
    public record DebugInfo(Vector3d up, Vector3d anchor, Aabb playerAabb,
                            Vector3d obbCenter, Vector3d penetrationNormal,
                            double penetrationDepth, Vector3d correction) {
        public DebugInfo {
            up = new Vector3d(up);
            anchor = new Vector3d(anchor);
            obbCenter = new Vector3d(obbCenter);
            penetrationNormal = penetrationNormal == null ? null : new Vector3d(penetrationNormal);
            correction = new Vector3d(correction);
        }
    }
}
