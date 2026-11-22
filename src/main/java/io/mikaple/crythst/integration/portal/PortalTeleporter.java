package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
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
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
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
 *     <li>entities and players cross when their <b>bounding-box centre</b>
 *     (feet + half height) passes the plane, in either direction. This matches
 *     the "roughly half the body has crossed" feel and avoids the eye being
 *     above a 2-block portal rectangle. Teleport placement still uses the
 *     transformed eye for players, so the camera stays continuous;</li>
 *     <li>the same rigid transform serves both directions: a front-to-back
 *     crossing exits on the destination front, a back-to-front crossing exits
 *     on the destination back, both moving away from the plane;</li>
 *     <li>non-player entities receive a tiny exit-side bias along transformed
 *     motion; physics bodies keep the bounded slab test;</li>
 *     <li>entities may cross dimensions via
 *     {@link Entity#teleport(TeleportTransition)}; physics bodies are skipped
 *     when a link crosses dimensions (their worlds are per-dimension).</li>
 * </ul>
 *
 * <p>See {@code docs/immersive-portal.md} for the remaining limitations.</p>
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
    /**
     * Upper bound for a client-reported crossing velocity. This is only a
     * sanity cap (10 blocks/tick) so a malformed packet cannot launch the
     * player; normal movement is far below it.
     */
    private static final double MAX_CLIENT_TRAVERSAL_SPEED = 10.0;
    private static final double MAX_CLIENT_TRAVERSAL_SPEED_SQUARED =
            MAX_CLIENT_TRAVERSAL_SPEED * MAX_CLIENT_TRAVERSAL_SPEED;
    /**
     * A client-only crossing prediction is accepted only when its intersection
     * point is within this radius of the server player's body centre. This
     * covers network latency without letting a packet claim a crossing from a
     * free-floating position anywhere near the portal.
     */
    private static final double MAX_CLIENT_CROSSING_DISTANCE = 2.5;

    private static final Map<UUID, Integer> ENTITY_READY = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BODY_READY = new ConcurrentHashMap<>();
    private static final Map<String, BodySample> BODY_PREVIOUS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PLAYER_ATTEMPT_LOG = new ConcurrentHashMap<>();
    private static final long CROSSING_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private static final double NEAR_PLAYER_LOG_DISTANCE_SQUARED = 16.0;
    private static long lastCrossingLogNanos = -CROSSING_LOG_INTERVAL_NANOS;

    private PortalTeleporter() {
    }

    public static void clearCooldowns() {
        ENTITY_READY.clear();
        BODY_READY.clear();
        BODY_PREVIOUS.clear();
        PLAYER_ATTEMPT_LOG.clear();
    }

    /**
     * Server-side validation of a client's continuous-crossing prediction.
     *
     * <p>The client sends only pre-transform samples. The server verifies that
     * the swept body-centre segment really crosses the authoritative polygon
     * near the portal, then applies the same rigid transform itself. This keeps
     * the server authoritative while preventing the movement-packet checker
     * from rubber-banding a valid client portal step.</p>
     */
    public static void acceptClientTraversal(ServerPlayer player,
                                             io.mikaple.crythst.network.portal.PortalTraversalPayload payload) {
        if (player == null || payload == null || player.isRemoved()
                || player.isSpectator() || player.isPassenger()) {
            return;
        }
        final MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        final Portal portal = PortalManager.findById(payload.portalId());
        final Portal destination = PortalManager.linkedPortal(portal);
        if (portal == null || destination == null
                || !portal.dimension().equals(player.level().dimension())
                || !destination.dimension().equals(player.level().dimension())) {
            return;
        }
        final int tick = server.getTickCount();
        final Integer ready = ENTITY_READY.get(player.getUUID());
        if (ready != null && tick < ready) {
            return;
        }

        // Reject non-finite samples first: NaN compares false against every
        // upper bound below, so a naive "too far" check silently accepts it and
        // then writes a NaN position/rotation into the player.
        if (!PortalTeleportMath.isFiniteSample(payload.previousCentre(),
                payload.currentCentre(), payload.eye())
                || !PortalTeleportMath.isFinite(payload.velocity())
                || !Float.isFinite(payload.yaw()) || !Float.isFinite(payload.pitch())) {
            return;
        }
        // The samples must be close to the entry opening, and the eye must be
        // consistent with the body centre. This bounds both replay and spoofing.
        if (portal.plane().distanceSquared(payload.currentCentre()) > 64.0
                || portal.plane().distanceSquared(payload.previousCentre()) > 64.0
                || payload.eye().distanceSquared(payload.currentCentre()) > 16.0) {
            return;
        }
        // The authoritative server position must be near the claimed crossing;
        // this rejects replayed samples after the player has moved away.
        if (player.position().distanceToSqr(new Vec3(
                payload.currentCentre().x, payload.currentCentre().y,
                payload.currentCentre().z)) > 64.0) {
            return;
        }

        // Prefer the server's own swept segment. The client samples are only a
        // prediction/timing hint: if the server already saw the movement, its
        // segment is authoritative. Otherwise require the claimed intersection
        // to be physically close to the server player.
        final Vector3d serverPrevious = previousTraversalPoint(player);
        final Vector3d serverCurrent = traversalPoint(player);
        final PortalCrossing.Crossing serverCrossing = PortalCrossing.findCrossing(
                portal.shape(), serverPrevious, serverCurrent);
        final PortalCrossing.Crossing clientCrossing = PortalCrossing.findCrossing(
                portal.shape(), payload.previousCentre(), payload.currentCentre());
        final boolean serverInside = serverCrossing != null && serverCrossing.inside();
        final boolean clientInside = clientCrossing != null && clientCrossing.inside();
        final Vec3 serverCentre = player.getBoundingBox().getCenter();
        final boolean accepted = PortalTraversalAuthority.accepts(
                serverInside, clientInside,
                clientCrossing == null ? null : clientCrossing.point(),
                new Vector3d(serverCentre.x, serverCentre.y, serverCentre.z),
                MAX_CLIENT_CROSSING_DISTANCE);
        if (!accepted) {
            if (clientInside) {
                // The client claims a crossing the server cannot corroborate
                // and is too far from the authoritative body centre. Undo the
                // local prediction instead of leaving a rubber-band.
                correctPlayer(player);
            }
            return;
        }
        final PortalTransform transform =
                PortalTransform.between(portal.plane(), destination.plane());
        // Placement, velocity and rotation come from the server player, never
        // from a client-supplied target vector.
        final Vec3 serverEyePos = player.getEyePosition();
        final Vector3d serverEye = new Vector3d(
                serverEyePos.x, serverEyePos.y, serverEyePos.z);
        final PortalCrossing.Crossing crossing = serverInside ? serverCrossing : clientCrossing;
        final Vector3d feet = PortalTeleportMath.playerFeetAfterCrossing(
                transform, serverEye, player.getEyeHeight(), serverCentre.y - player.getY(),
                crossing.alongPositiveDirection());
        Vec3 serverMovement = player.getKnownMovement();
        if (serverMovement.lengthSqr() < 1.0e-8) {
            serverMovement = player.getDeltaMovement();
        }
        final Vector3d velocity = transform.transformDirection(new Vector3d(
                serverMovement.x, serverMovement.y, serverMovement.z));
        final double speedSquared = velocity.lengthSquared();
        if (speedSquared > MAX_CLIENT_TRAVERSAL_SPEED_SQUARED) {
            velocity.mul(MAX_CLIENT_TRAVERSAL_SPEED / Math.sqrt(speedSquared));
        }
        final float[] rotation = PortalTransform.yawPitch(transform.transformDirection(
                PortalTransform.viewDirection(player.getYRot(), player.getXRot())));
        if (player.connection != null) {
            player.connection.teleport(new PositionMoveRotation(
                    new Vec3(feet.x, feet.y, feet.z),
                    new Vec3(velocity.x, velocity.y, velocity.z),
                    rotation[0], rotation[1]), Set.<Relative>of());
        }
        player.setPos(feet.x, feet.y, feet.z);
        player.setYRot(rotation[0]);
        player.setXRot(rotation[1]);
        player.setDeltaMovement(velocity.x, velocity.y, velocity.z);
        player.setOldPosAndRot();
        ENTITY_READY.put(player.getUUID(), tick + COOLDOWN_TICKS);
    }

    /** Sends the authoritative server pose to undo an unverifiable prediction. */
    private static void correctPlayer(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        player.connection.teleport(new PositionMoveRotation(
                player.position(),
                player.getDeltaMovement(),
                player.getYRot(),
                player.getXRot()), Set.<Relative>of());
        player.setOldPosAndRot();
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
        BODY_PREVIOUS.entrySet().removeIf(entry -> tick - entry.getValue().tick() > 100);

        final Set<UUID> teleportedEntities = new HashSet<>();
        final Set<String> teleportedBodies = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            final List<Portal> portals = PortalManager.portalsIn(level.dimension());
            if (portals.isEmpty()) {
                continue;
            }
            // Snapshot the entity list once per level. A cross-dimension
            // teleport removes the source entity from the live, non-fail-fast
            // visible-entity map while it is being iterated, which can skip or
            // repeat other entities. A copy also avoids re-iterating the live
            // collection once per portal.
            final List<Entity> entities = new ArrayList<>();
            level.getAllEntities().forEach(entities::add);

            for (Portal portal : portals) {
                final Portal destination = PortalManager.linkedPortal(portal);
                if (destination == null) {
                    continue;
                }
                final ServerLevel destinationLevel = server.getLevel(destination.dimension());
                if (destinationLevel == null) {
                    continue;
                }

                // One rigid transform preserves the local direction of travel.
                final PortalTransform transform =
                        PortalTransform.between(portal.plane(), destination.plane());

                // Entities first: they are cheap and keep the player experience
                // consistent even when the physics backend is unavailable.
                for (Entity entity : entities) {
                    if (!isEligible(entity) || teleportedEntities.contains(entity.getUUID())) {
                        continue;
                    }
                    // The body centre crossing gives the half-in/half-out feel;
                    // teleport placement still targets the transformed eye.
                    final Vector3d beforePoint = previousTraversalPoint(entity);
                    final Vector3d afterPoint = traversalPoint(entity);
                    final PortalCrossing.Crossing crossing =
                            PortalCrossing.findCrossing(portal.shape(), beforePoint, afterPoint);
                    final Integer ready = ENTITY_READY.get(entity.getUUID());
                    final boolean cooldown = ready != null && tick < ready;
                    final boolean nearPlayer = entity instanceof ServerPlayer
                            && portal.plane().distanceSquared(afterPoint)
                            <= NEAR_PLAYER_LOG_DISTANCE_SQUARED;

                    String result = "no crossing";
                    boolean teleported = false;
                    if (crossing != null && !crossing.inside()) {
                        result = "outside opening";
                    } else if (crossing != null && cooldown) {
                        result = "cooldown";
                    } else if (crossing != null) {
                        teleported = teleportEntity(
                                entity, level, destination, destinationLevel, transform, crossing);
                        if (teleported) {
                            teleportedEntities.add(entity.getUUID());
                            ENTITY_READY.put(entity.getUUID(), tick + COOLDOWN_TICKS);
                            result = "teleported";
                        } else {
                            result = "teleport rejected";
                        }
                    }

                    if (nearPlayer) {
                        logNearPlayerAttempt(portal, (ServerPlayer) entity,
                                crossing, beforePoint, afterPoint,
                                cooldown, true, result);
                    } else if (crossing != null) {
                        logCrossingAttempt(portal, entity, crossing, result);
                    }
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
     * map. The sample is the bounding-box centre for every entity, so a portal
     * rectangle below the eye still contains the torso.</p>
     */
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

    private static void logNearPlayerAttempt(Portal portal, ServerPlayer player,
                                             PortalCrossing.Crossing crossing,
                                             Vector3d beforePoint, Vector3d afterPoint,
                                             boolean cooldown, boolean linkResolved,
                                             String result) {
        final long now = System.nanoTime();
        final Long last = PLAYER_ATTEMPT_LOG.get(player.getUUID());
        if (last != null && now - last < CROSSING_LOG_INTERVAL_NANOS) {
            return;
        }
        PLAYER_ATTEMPT_LOG.put(player.getUUID(), now);

        final PortalPlane plane = portal.plane();
        final Vec3 eye = player.getEyePosition();
        final boolean eyeInside = plane.contains(
                new Vector3d(eye.x, eye.y, eye.z), PLANE_THICKNESS);
        final boolean bodyInside = crossing != null
                ? crossing.inside()
                : plane.contains(afterPoint, PLANE_THICKNESS);

        Crythst.LOGGER.info(
                "Crythst portal player attempt: portal='{}' player={} signedBefore={} "
                        + "signedAfter={} bodyInside={} eyeInside={} cooldown={} "
                        + "linkResolved={} result={}",
                portal.name(), player.getName().getString(),
                String.format("%.3f", plane.signedDistance(beforePoint)),
                String.format("%.3f", plane.signedDistance(afterPoint)),
                bodyInside, eyeInside, cooldown, linkResolved, result);
    }

    private static Vector3d traversalPoint(Entity entity) {
        // Use the body centre (feet + height/2), not the eye. A portal is
        // created around eye level, so its 2-block rectangle can sit entirely
        // below the eye while still covering the torso. Testing the centre also
        // gives the desired "roughly half the body has crossed" trigger.
        final Vec3 centre = entity.getBoundingBox().getCenter();
        return new Vector3d(centre.x, centre.y, centre.z);
    }

    private static Vector3d previousTraversalPoint(Entity entity) {
        // Shift the current bbox centre back by this tick's position delta.
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
                                          PortalTransform transform, PortalCrossing.Crossing crossing) {
        final Vector3d velocity = new Vector3d(
                entity.getDeltaMovement().x,
                entity.getDeltaMovement().y,
                entity.getDeltaMovement().z);
        final Vector3d transformedVelocity = transform.transformDirection(velocity);

        final float[] rotation = PortalTransform.yawPitch(transform.transformDirection(
                PortalTransform.viewDirection(entity.getYRot(), entity.getXRot())));

        final Vector3d base = entity instanceof ServerPlayer player
                ? PortalTeleportMath.playerFeetAfterCrossing(
                        transform, new Vector3d(
                                player.getEyePosition().x,
                                player.getEyePosition().y,
                                player.getEyePosition().z),
                        player.getEyeHeight(), player.getBoundingBox().getCenter().y - player.getY(),
                        crossing.alongPositiveDirection())
                : transform.transformPoint(new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
        final Vector3d target = new Vector3d(base);
        if (!(entity instanceof ServerPlayer)) {
            // Other entities get a tiny bias along their transformed motion.
            final Vector3d entryLocal = transform.entry().local(
                    new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
            target.add(PortalTeleportMath.exitBias(
                    destination.shape().plane(), transformedVelocity, entryLocal)
                    .mul(ENTITY_EXIT_OFFSET));
        }

        final double fallDistance = entity.fallDistance;
        final boolean onGround = entity.onGround();
        final Vector3d oldPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        final boolean sameDimension = destination.dimension().equals(level.dimension());
        Entity teleported = entity;

        if (sameDimension) {
            applyLocalTeleport(entity, target, rotation[0], rotation[1], transformedVelocity);
            // A locally teleported vehicle carries its passengers with the same
            // rigid offset; vanilla's position packet path does this itself for
            // players with passengers, but not for setPos on other entities.
            movePassengers(entity, oldPosition, target, transform);
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
            teleported = result;
            if (result != entity) {
                result.setDeltaMovement(transformedVelocity.x, transformedVelocity.y,
                        transformedVelocity.z);
                result.fallDistance = fallDistance;
                result.setOnGround(onGround);
            }
        }

        teleported.fallDistance = fallDistance;
        teleported.setOnGround(onGround);
        if (!sameDimension
                && teleported instanceof net.minecraft.world.entity.Leashable leashable
                && leashable.isLeashed()) {
            // A leash cannot follow a dimension change. Same-dimension crossings
            // keep the leash intact; the vanilla leash tick then updates the
            // rope between the transformed endpoints. Use the teleported copy:
            // Entity.teleport copies the leash state *before* removing it from
            // the old entity, so checking the source leaves the new entity
            // leashed to an entity in the old dimension.
            leashable.removeLeash();
        }
        return true;
    }

    private static void movePassengers(Entity vehicle, Vector3d oldVehiclePosition,
                                       Vector3d newVehiclePosition, PortalTransform transform) {
        if (!vehicle.isVehicle()) {
            return;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            final Vector3d relative = new Vector3d(
                    passenger.getX() - oldVehiclePosition.x,
                    passenger.getY() - oldVehiclePosition.y,
                    passenger.getZ() - oldVehiclePosition.z);
            transform.transformDirection(relative);
            final Vector3d destination = new Vector3d(newVehiclePosition).add(relative);
            final float[] rotation = PortalTransform.yawPitch(transform.transformDirection(
                    PortalTransform.viewDirection(passenger.getYRot(), passenger.getXRot())));
            if (passenger instanceof ServerPlayer player && player.connection != null) {
                player.connection.teleport(new PositionMoveRotation(
                        new Vec3(destination.x, destination.y, destination.z),
                        Vec3.ZERO, rotation[0], rotation[1]),
                        Set.<Relative>of());
            } else {
                passenger.setPos(destination.x, destination.y, destination.z);
                passenger.setYRot(rotation[0]);
                passenger.setXRot(rotation[1]);
                passenger.setOldPosAndRot();
            }
        }
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

            final String key = level.dimension().identifier() + "#" + body.id();
            final Vector3d centre = body.transform().position();
            final BodySample previous = BODY_PREVIOUS.get(key);
            BODY_PREVIOUS.put(key, new BodySample(new Vector3d(centre), tick));

            final boolean crossed;
            final boolean inside;
            if (previous != null && tick - previous.tick() <= 2) {
                final PortalCrossing.Crossing crossing = PortalCrossing.findCrossing(
                        portal.shape(), previous.centre(), centre);
                crossed = crossing != null;
                inside = crossing != null && crossing.inside();
            } else {
                crossed = portal.shape().contains(centre, PLANE_THICKNESS);
                inside = crossed;
            }
            if (!crossed || !inside) {
                continue;
            }

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
            final Vector3d gravityOverride = body.gravityOverride();
            final Vector3d transformedGravity = gravityOverride == null ? null
                    : transform.transformDirection(gravityOverride);
            final Vector3d push = PortalTeleportMath.exitBias(
                    destination.shape().plane(), linear, portal.plane().local(centre));

            final PhysicsStructure structure = body.userData() instanceof PhysicsStructure s ? s : null;

            if (structure != null) {
                final Transform3d pose = structure.pose();
                final Vector3d position = transform.transformPoint(pose.position())
                        .add(new Vector3d(push).mul(EXIT_OFFSET));
                final Quaterniond rotation = transform.transformRotation(pose.rotation());
                structure.teleport(new Transform3d(position, rotation));
                structure.body().setLinearVelocity(linear);
                structure.body().setAngularVelocity(angular);
                if (transformedGravity != null) {
                    structure.body().setGravityOverride(transformedGravity);
                }
                structure.body().activate();
                BODY_PREVIOUS.put(key, new BodySample(
                        new Vector3d(structure.body().transform().position()), tick));
            } else {
                final Transform3d pose = body.transform();
                final Vector3d position = transform.transformPoint(pose.position())
                        .add(new Vector3d(push).mul(EXIT_OFFSET));
                final Quaterniond rotation = transform.transformRotation(pose.rotation());
                body.teleport(new Transform3d(position, rotation));
                body.setLinearVelocity(linear);
                body.setAngularVelocity(angular);
                if (transformedGravity != null) {
                    body.setGravityOverride(transformedGravity);
                }
                body.activate();
                BODY_PREVIOUS.put(key, new BodySample(
                        new Vector3d(body.transform().position()), tick));
            }

            BODY_READY.put(key, tick + COOLDOWN_TICKS);
        }
    }

    private record BodySample(Vector3d centre, int tick) {
    }


}
