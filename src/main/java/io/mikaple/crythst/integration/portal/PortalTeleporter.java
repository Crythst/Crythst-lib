package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.TerrainBody;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End-of-tick portal traversal for entities, players and physics bodies.
 *
 * <p>The pass runs after {@code MinecraftPhysics::tick} (registration order)
 * and is deliberately conservative:</p>
 * <ul>
 *     <li>an entity/body is teleported at most once per tick;</li>
 *     <li>a ten-tick cooldown prevents the destination portal from
 *     re-triggering immediately;</li>
 *     <li>players cross when their <b>eye</b> passes the plane from the front
 *     side to the back side, rather than when their bounding-box centre enters
 *     a thin slab. This lets the player physically walk into the portal: half
 *     the body may still be on the entry side until the camera crosses.
 *     Back-to-front crossings are ignored so the exit always stays on the
 *     destination's front side;</li>
 *     <li>non-player living entities use their eye position, other entities
 *     use their bounding-box centre; their exit point is pushed slightly out
 *     of the destination plane along its front normal;</li>
 *     <li>entities may cross dimensions via
 *     {@link Entity#teleport(TeleportTransition)}; physics bodies are skipped
 *     when a link crosses dimensions (their worlds are per-dimension).</li>
 * </ul>
 *
 * <p>Physics bodies still use their oriented bounding-box centre against the
 * bounded trigger slab, because they have no camera/eye to cross with. See
 * {@code docs/immersive-portal.md} for the remaining limitations.</p>
 */
public final class PortalTeleporter {
    /** Half-thickness of the trigger slab used by physics bodies, in blocks. */
    public static final double PLANE_THICKNESS = 0.35;
    /** Distance a physics body is pushed out of the destination plane. */
    public static final double EXIT_OFFSET = 0.6;
    /** Small front-side bias for non-player entities. */
    public static final double ENTITY_EXIT_OFFSET = 0.05;
    /** Ticks before the same entity/body may traverse again. */
    public static final int COOLDOWN_TICKS = 10;

    private static final Map<UUID, Integer> ENTITY_READY = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BODY_READY = new ConcurrentHashMap<>();
    private static final long CROSSING_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private static long lastCrossingLogNanos = -CROSSING_LOG_INTERVAL_NANOS;

    private PortalTeleporter() {
    }

    public static void clearCooldowns() {
        ENTITY_READY.clear();
        BODY_READY.clear();
    }

    /** Fabric {@code END_SERVER_TICK} callback. */
    public static void tick(MinecraftServer server) {
        if (PortalManager.size() == 0) {
            clearCooldowns();
            return;
        }

        final int tick = server.getTickCount();
        ENTITY_READY.entrySet().removeIf(entry -> entry.getValue() <= tick);
        BODY_READY.entrySet().removeIf(entry -> entry.getValue() <= tick);

        final Set<UUID> teleportedEntities = new HashSet<>();
        final Set<String> teleportedBodies = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            final List<Portal> portals = PortalManager.portalsIn(level.dimension());
            if (portals.isEmpty()) {
                continue;
            }

            for (Portal portal : portals) {
                final Portal destination = PortalManager.linkedPortal(portal);
                if (destination == null) {
                    continue;
                }
                final ServerLevel destinationLevel = server.getLevel(destination.dimension());
                if (destinationLevel == null) {
                    continue;
                }

                // One rigid transform for both directions: the transformed
                // entry side is the destination side it leaves through, and
                // the transformed velocity always points away from the plane.
                final PortalTransform transform =
                        PortalTransform.between(portal.plane(), destination.plane());

                // Entities first: they are cheap and keep the player experience
                // consistent even when the physics backend is unavailable.
                for (Entity entity : level.getAllEntities()) {
                    if (!isEligible(entity) || teleportedEntities.contains(entity.getUUID())) {
                        continue;
                    }
                    // The eye crossing is what the player sees and what makes
                    // the transition feel immersive: the body may already be
                    // half-way through the surface when the camera follows it.
                    final PortalCrossing.Crossing crossing = crossing(portal.plane(), entity);
                    if (crossing == null) {
                        continue;
                    }
                    final Integer ready = ENTITY_READY.get(entity.getUUID());
                    if (!crossing.inside()) {
                        logCrossingAttempt(portal, entity, crossing, "outside rectangle");
                        continue;
                    }
                    if (ready != null && tick < ready) {
                        logCrossingAttempt(portal, entity, crossing, "cooldown");
                        continue;
                    }
                    final boolean teleported =
                            teleportEntity(entity, level, destination, destinationLevel, transform);
                    if (teleported) {
                        teleportedEntities.add(entity.getUUID());
                        ENTITY_READY.put(entity.getUUID(), tick + COOLDOWN_TICKS);
                    }
                    logCrossingAttempt(portal, entity, crossing,
                            teleported ? "teleported" : "teleport rejected");
                }

                // Physics bodies can only stay inside one physics world.
                if (!destination.dimension().equals(level.dimension())) {
                    continue;
                }
                teleportBodies(level, portal, destination, transform, tick,
                        teleportedBodies);
            }
        }
    }

    /**
     * Segment/plane crossing for this tick, in either direction.
     *
     * <p>Using the previous-tick position ({@code xo/yo/zo}) and the current
     * position also catches fast entity movement without a separate history
     * map. Players use the eye position so the camera, not the body centre,
     * is the thing that enters the plane.</p>
     */
    private static PortalCrossing.Crossing crossing(PortalPlane plane, Entity entity) {
        return PortalCrossing.findCrossing(
                plane, previousTraversalPoint(entity), traversalPoint(entity));
    }

    private static void logCrossingAttempt(Portal portal, Entity entity,
                                           PortalCrossing.Crossing crossing, String result) {
        final long now = System.nanoTime();
        if (now - lastCrossingLogNanos < CROSSING_LOG_INTERVAL_NANOS) {
            return;
        }
        lastCrossingLogNanos = now;
        Crythst.LOGGER.info(
                "Crythst portal crossing: portal='{}' entity={} before={} after={} inside={} "
                        + "frontToBack={} result={}",
                portal.name(),
                entity.getName().getString() + "/" + entity.getUUID(),
                String.format("%.3f", crossing.before()),
                String.format("%.3f", crossing.after()),
                crossing.inside(), crossing.frontToBack(), result);
    }

    private static Vector3d traversalPoint(Entity entity) {
        final Vec3 point = entity instanceof LivingEntity
                ? entity.getEyePosition()
                : entity.getBoundingBox().getCenter();
        return new Vector3d(point.x, point.y, point.z);
    }

    private static Vector3d previousTraversalPoint(Entity entity) {
        if (entity instanceof LivingEntity) {
            // Eye height is effectively constant across one tick; keeping the
            // current value avoids a second pose lookup and matches the point
            // that is actually rendered.
            return new Vector3d(entity.xo, entity.yo + entity.getEyeHeight(), entity.zo);
        }
        final Vec3 centre = entity.getBoundingBox().getCenter();
        return new Vector3d(
                centre.x - (entity.getX() - entity.xo),
                centre.y - (entity.getY() - entity.yo),
                centre.z - (entity.getZ() - entity.zo));
    }

    private static boolean isEligible(Entity entity) {
        return !entity.isRemoved()
                && entity.isAlive()
                && !entity.isSpectator()
                && !entity.isPassenger()
                && !entity.noPhysics
                && !(entity instanceof Display)
                && !(entity instanceof EnderDragonPart);
    }

    private static boolean teleportEntity(Entity entity, ServerLevel level,
                                          Portal destination, ServerLevel destinationLevel,
                                          PortalTransform transform) {
        final Vector3d velocity = new Vector3d(
                entity.getDeltaMovement().x,
                entity.getDeltaMovement().y,
                entity.getDeltaMovement().z);
        final Vector3d transformedVelocity = transform.transformDirection(velocity);

        final float[] rotation = PortalTransform.yawPitch(transform.transformDirection(
                PortalTransform.viewDirection(entity.getYRot(), entity.getXRot())));

        final Vector3d base = entity instanceof ServerPlayer player
                ? transformedPlayerFeet(player, transform)
                : transform.transformPoint(new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
        final Vector3d target = new Vector3d(base);
        if (!(entity instanceof ServerPlayer)) {
            // The player's eye is placed exactly at T(eye): at the crossing
            // instant the new camera position equals the transformed view
            // through the portal, so the transition is geometrically
            // continuous. Other entities get a tiny bias on the exit side the
            // transformed motion actually leaves through (front for a
            // front-to-back crossing, back for a back-to-front crossing).
            final Vector3d entryLocal = transform.entry().local(
                    new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
            target.add(pushDirection(destination.plane(), transformedVelocity, entryLocal)
                    .mul(ENTITY_EXIT_OFFSET));
        }

        final double fallDistance = entity.fallDistance;
        final boolean onGround = entity.onGround();

        if (destination.dimension().equals(level.dimension())) {
            applyLocalTeleport(entity, target, rotation[0], rotation[1], transformedVelocity);
        } else {
            final TeleportTransition transition = new TeleportTransition(
                    destinationLevel,
                    new Vec3(target.x, target.y, target.z),
                    new Vec3(transformedVelocity.x, transformedVelocity.y, transformedVelocity.z),
                    rotation[0], rotation[1],
                    Set.<Relative>of(),
                    TeleportTransition.DO_NOTHING);
            final Entity result = entity.teleport(transition);
            if (result == null) {
                return false;
            }
            if (result != entity) {
                result.setDeltaMovement(transformedVelocity.x, transformedVelocity.y,
                        transformedVelocity.z);
                result.fallDistance = fallDistance;
                result.setOnGround(onGround);
            }
        }

        entity.fallDistance = fallDistance;
        entity.setOnGround(onGround);
        return true;
    }

    /**
     * Places the player's feet so the vanilla camera (feet + eye height along
     * world up) ends up at the transformed eye position. This keeps the view
     * continuous through the portal; tilted portals still do not rotate the
     * player's gravity/up vector, which is a later milestone.
     */
    private static Vector3d transformedPlayerFeet(ServerPlayer player, PortalTransform transform) {
        final Vec3 eye = player.getEyePosition();
        final Vector3d transformedEye = transform.transformPoint(
                new Vector3d(eye.x, eye.y, eye.z));
        return transformedEye.sub(0.0, player.getEyeHeight(), 0.0);
    }

    private static void applyLocalTeleport(Entity entity, Vector3d target,
                                           float yaw, float pitch, Vector3d velocity) {
        if (entity instanceof ServerPlayer player && player.connection != null) {
            // Include the transformed velocity in the position packet so the
            // client does not lose momentum at the portal seam.
            player.connection.teleport(
                    new PositionMoveRotation(
                            new Vec3(target.x, target.y, target.z),
                            new Vec3(velocity.x, velocity.y, velocity.z),
                            yaw, pitch),
                    Set.<Relative>of());
            player.setDeltaMovement(velocity.x, velocity.y, velocity.z);
            player.setYRot(yaw);
            player.setXRot(pitch);
            return;
        }

        entity.setPos(target.x, target.y, target.z);
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.setOldPosAndRot();
        entity.setDeltaMovement(velocity.x, velocity.y, velocity.z);
    }

    private static void teleportBodies(ServerLevel level, Portal portal, Portal destination,
                                       PortalTransform transform, int tick,
                                       Set<String> teleportedBodies) {
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics == null || physics.closed()) {
            return;
        }

        final List<BodyHandle> bodies = List.copyOf(physics.world().bodies());
        for (BodyHandle body : bodies) {
            if (body.userData() instanceof TerrainBody
                    || body.type() == BodyType.STATIC
                    || body.removeRequested()
                    || !body.inWorld()) {
                continue;
            }

            final OrientedBox obb = body.obb();
            final Vector3d centre = obb.center();
            if (!portal.plane().contains(centre, PLANE_THICKNESS)) {
                continue;
            }

            final String key = level.dimension().identifier() + "#" + body.id();
            if (!teleportedBodies.add(key)) {
                continue;
            }
            final Integer ready = BODY_READY.get(key);
            if (ready != null && tick < ready) {
                teleportedBodies.remove(key);
                continue;
            }

            final Vector3d linear = transform.transformDirection(new Vector3d(body.linearVelocity()));
            final Vector3d angular = transform.transformDirection(new Vector3d(body.angularVelocity()));
            final Vector3d push = pushDirection(destination.plane(), linear,
                    portal.plane().local(centre));

            final PhysicsStructure structure = body.userData() instanceof PhysicsStructure s ? s : null;

            if (structure != null) {
                final Transform3d pose = structure.pose();
                final Vector3d position = transform.transformPoint(pose.position())
                        .add(new Vector3d(push).mul(EXIT_OFFSET));
                final Quaterniond rotation = transform.transformRotation(pose.rotation());
                structure.teleport(new Transform3d(position, rotation));
                structure.body().setLinearVelocity(linear);
                structure.body().setAngularVelocity(angular);
                structure.body().activate();
            } else {
                final Transform3d pose = body.transform();
                final Vector3d position = transform.transformPoint(pose.position())
                        .add(new Vector3d(push).mul(EXIT_OFFSET));
                final Quaterniond rotation = transform.transformRotation(pose.rotation());
                body.teleport(new Transform3d(position, rotation));
                body.setLinearVelocity(linear);
                body.setAngularVelocity(angular);
                body.activate();
            }

            BODY_READY.put(key, tick + COOLDOWN_TICKS);
        }
    }

    /**
     * Unit vector along the destination normal in the direction the object is
     * already travelling (or, for a nearly stationary object, the side of the
     * entry slab it came from).
     */
    private static Vector3d pushDirection(PortalPlane destination, Vector3d transformedVelocity,
                                          Vector3d entryLocal) {
        final Vector3d normal = destination.normal();
        double side = Math.signum(transformedVelocity.dot(normal));
        if (side == 0.0) {
            side = -Math.signum(entryLocal.z);
        }
        if (side == 0.0) {
            side = 1.0;
        }
        return normal.mul(side);
    }

}
