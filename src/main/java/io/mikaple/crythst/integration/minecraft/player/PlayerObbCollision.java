package io.mikaple.crythst.integration.minecraft.player;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Post-move collision correction for players with non-default gravity.
 *
 * <p>Vanilla movement still predicts a Y-up AABB. This coordinator therefore
 * runs at the end of the player tick, builds the player's {@link PlayerObb},
 * finds the shallowest penetration against nearby terrain and physics
 * structures, and moves the player out along the minimum translation vector.
 * It is a correction pass, not a replacement for vanilla's swept movement.</p>
 *
 * <p>The same code runs on the integrated client and the dedicated server:
 * client-side it provides local prediction against terrain; server-side it also
 * checks Crythst's physics structures. On a server the corrected position is
 * sent with {@code ServerPlayer.connection.teleport}, so a strongly divergent
 * client prediction can rubber-band. This is documented in
 * {@code docs/player-obb-collision.md}.</p>
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
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            final Vector3d push = findPush(player, up);
            if (push == null || push.lengthSquared() < MIN_PUSH * MIN_PUSH) {
                break;
            }

            final Vector3d delta = new Vector3d(push).mul(strength);
            final double length = delta.length();
            if (length > MAX_PUSH_PER_ITERATION) {
                delta.mul(MAX_PUSH_PER_ITERATION / length);
            }
            player.setPos(
                    player.getX() + delta.x,
                    player.getY() + delta.y,
                    player.getZ() + delta.z
            );
            total.add(delta);
        }

        if (total.lengthSquared() < MIN_PUSH * MIN_PUSH) {
            return;
        }

        // Remove the component of velocity that still points into the surface.
        // Vanilla gravity will add it again next tick, but the position
        // correction stops it from accumulating until the player sinks.
        final Vector3d outward = new Vector3d(total).normalize();
        final Vec3 motion = player.getDeltaMovement();
        final double intoSurface = motion.x * outward.x
                + motion.y * outward.y
                + motion.z * outward.z;
        if (intoSurface < 0.0) {
            player.setDeltaMovement(
                    motion.x - outward.x * intoSurface,
                    motion.y - outward.y * intoSurface,
                    motion.z - outward.z * intoSurface
            );
        }

        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
            // The server is authoritative. Send the corrected position instead
            // of letting the vanilla tracker interpolate through geometry.
            serverPlayer.connection.teleport(
                    serverPlayer.getX(),
                    serverPlayer.getY(),
                    serverPlayer.getZ(),
                    serverPlayer.getYRot(),
                    serverPlayer.getXRot()
            );
        }
    }

    /**
     * Finds the shallowest penetration against all local obstacles.
     *
     * @return a world-space push vector, or {@code null} when free
     */
    private static Vector3d findPush(Player player, Vector3d up) {
        final OrientedBox box = PlayerObb.obb(player, up);
        final Aabb broad = box.toAabb().expand(BROADPHASE_MARGIN);
        final Level level = player.level();

        Vector3d best = null;
        double bestLength = Double.POSITIVE_INFINITY;

        final net.minecraft.world.phys.AABB query = new net.minecraft.world.phys.AABB(
                broad.min().x, broad.min().y, broad.min().z,
                broad.max().x, broad.max().y, broad.max().z
        );
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
                final double length = lengthSquared(push);
                if (push != null && length < bestLength) {
                    best = push;
                    bestLength = length;
                }
            }
        }

        if (player instanceof ServerPlayer serverPlayer) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverPlayer.level());
            if (physics != null) {
                for (PhysicsStructure structure : physics.structures()) {
                    if (structure.closed() || !structure.aabb().intersects(broad)) {
                        continue;
                    }
                    final Vector3d push = structurePenetration(box, structure);
                    final double length = lengthSquared(push);
                    if (push != null && length < bestLength) {
                        best = push;
                        bestLength = length;
                    }
                }
            }
        }

        return best;
    }

    /**
     * Tests the player box against every merged voxel box of one structure.
     */
    private static Vector3d structurePenetration(OrientedBox playerBox, PhysicsStructure structure) {
        final Transform3d pose = structure.pose();
        final Quaterniond rotation = pose.rotation();
        final VoxelColliderData collider = structure.collider();
        final double scale = structure.blockScale();

        Vector3d best = null;
        double bestLength = Double.POSITIVE_INFINITY;

        for (VoxelBox voxel : collider.boxes()) {
            // structure.pose() maps unshifted voxel coordinates. Do not
            // subtract the centre of mass again: that is a body-pose local
            // convention and would shift every box by -R*COM.
            final Vector3d localCenter = new Vector3d(
                    voxel.minX() + voxel.sizeX() * 0.5,
                    voxel.minY() + voxel.sizeY() * 0.5,
                    voxel.minZ() + voxel.sizeZ() * 0.5
            ).mul(scale);
            final Vector3d worldCenter = pose.transformPoint(localCenter);
            final Vector3d halfExtents = new Vector3d(
                    voxel.sizeX(), voxel.sizeY(), voxel.sizeZ()
            ).mul(scale * 0.5);

            final OrientedBox obstacle = new OrientedBox(worldCenter, halfExtents, rotation);
            final Vector3d push = PlayerObb.penetration(playerBox, obstacle);
            final double length = lengthSquared(push);
            if (push != null && length < bestLength) {
                best = push;
                bestLength = length;
            }
        }

        return best;
    }

    private static double lengthSquared(Vector3d vector) {
        return vector == null ? Double.POSITIVE_INFINITY : vector.lengthSquared();
    }

    /**
     * Exposed for diagnostics/tests: the current player OBB, or {@code null}
     * when vanilla gravity is active.
     */
    public static OrientedBox currentBox(Player player) {
        final Vector3d up = PlayerObb.upOrNull(player);
        return up == null ? null : PlayerObb.obb(player, up);
    }
}
