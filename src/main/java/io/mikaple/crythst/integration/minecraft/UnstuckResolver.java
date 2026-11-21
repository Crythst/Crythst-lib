package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.TerrainBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Last-resort de-penetration for dynamic bodies whose centre ends up inside a
 * solid world block.
 *
 * <p>Bullet resolves shallow contacts itself, but a body spawned inside a wall
 * or dropped into a fully enclosed block has no useful contact normal and can
 * stay stuck. This resolver samples the six axis directions for the nearest
 * non-solid cell and applies a central impulse along that direction, repeating
 * every {@code unstuckIntervalTicks}. It deliberately does not teleport: normal
 * contacts, structures and player movement continue to behave normally.</p>
 */
public final class UnstuckResolver {
    private static final Vector3d[] DIRECTIONS = {
            new Vector3d(0, 1, 0),
            new Vector3d(1, 0, 0),
            new Vector3d(-1, 0, 0),
            new Vector3d(0, 0, 1),
            new Vector3d(0, 0, -1),
            new Vector3d(0, -1, 0)
    };

    private final ServerLevel level;
    private final PhysicsWorld world;
    private final CrythstConfig config;
    private int ticks;

    public UnstuckResolver(ServerLevel level, PhysicsWorld world, CrythstConfig config) {
        this.level = level;
        this.world = world;
        this.config = config;
    }

    /**
     * Called once per server tick; internally throttled.
     *
     * @return number of bodies that received an outward impulse
     */
    public int tick() {
        if (!config.unstuckEnabled || ticks++ % Math.max(1, config.unstuckIntervalTicks) != 0) {
            return 0;
        }
        int pushed = 0;
        for (BodyHandle body : world.bodies()) {
            if (body.type() != BodyType.DYNAMIC
                    || body.userData() == TerrainBody.INSTANCE
                    || body.removeRequested()) {
                continue;
            }
            final Vector3d center = body.transform().position();
            if (!isSolid(center)) {
                continue;
            }
            final Vector3d escape = findEscape(center);
            if (escape == null) {
                continue;
            }
            // Do not pile impulses onto an already-fast body: that was the
            // cause of "a box flying around" when a body was repeatedly pushed
            // while it was still resolving the previous escape.
            if (body.linearVelocity().length() > config.unstuckMaxSpeed) {
                continue;
            }
            // Apply an impulse, not a teleport. Scaling by mass makes the
            // resulting velocity change independent of the body's mass.
            body.applyCentralImpulse(new Vector3d(escape)
                    .mul(body.mass())
                    .mul(config.unstuckStrength));
            body.activate();
            pushed++;
            Crythst.LOGGER.debug("Pushed body #{} out of a solid block along {}",
                    body.id(), escape);
        }
        if (config.playerUnstuckEnabled) {
            pushed += unstickPlayers();
        }
        return pushed;
    }

    /**
     * A player embedded in a block cannot be helped by Bullet: vanilla movement
     * is client-authoritative and the client predicts the same collision. Move
     * the server player a small distance toward the nearest free cell and sync
     * the correction. This is intentionally gentle and only runs while the
     * player's chest is actually inside a collision shape.
     */
    private int unstickPlayers() {
        int moved = 0;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.getAbilities().flying) {
                continue;
            }
            final var box = player.getBoundingBox();
            final Vector3d center = new Vector3d(
                    (box.minX + box.maxX) * 0.5,
                    (box.minY + box.maxY) * 0.5,
                    (box.minZ + box.maxZ) * 0.5);
            if (!isSolid(center)) {
                continue;
            }
            final Vector3d escape = findEscape(center);
            if (escape == null) {
                continue;
            }
            player.setPos(
                    player.getX() + escape.x * 0.35,
                    player.getY() + escape.y * 0.35,
                    player.getZ() + escape.z * 0.35
            );
            player.setDeltaMovement(escape.x * 0.15, Math.max(0.15, escape.y * 0.15), escape.z * 0.15);
            player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
            moved++;
        }
        return moved;
    }

    /** Forces a pass regardless of the interval; used by the debug command. */
    public int force() {
        ticks = 0;
        return tick();
    }

    private boolean isSolid(Vector3d point) {
        final BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
        final BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    private Vector3d findEscape(Vector3d center) {
        final double[] distances = {1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0};
        for (double distance : distances) {
            if (distance > config.unstuckMaxDistance) {
                break;
            }
            for (Vector3d direction : DIRECTIONS) {
                final Vector3d candidate = new Vector3d(center).add(
                        new Vector3d(direction).mul(distance));
                if (!isSolid(candidate)) {
                    return direction;
                }
            }
        }
        // Fully enclosed or outside the search range: push up as a last resort.
        return new Vector3d(0, 1, 0);
    }
}
