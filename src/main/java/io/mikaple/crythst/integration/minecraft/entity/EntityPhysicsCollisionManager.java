package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One-way collision between normal Minecraft entities and physics structures.
 *
 * <p>Runs once per server tick, after the physics world has advanced. Every
 * eligible entity is tested against the merged voxel boxes of nearby
 * structures; an overlapping entity is translated out along the shallowest
 * face axis. The structure body is only read (pose plus immutable collider
 * data) &mdash; no force, impulse or transform is ever applied to it, so mobs,
 * items and players can never push a structure.</p>
 *
 * <p>This deliberately complements, rather than replaces, vanilla entity
 * movement. Vanilla still collides entities with world blocks; this pass adds
 * the missing collision response for blocks that live in a physics structure.
 * See {@code docs/entity-physics-collision.md} for the approximation and its
 * limitations.</p>
 */
public final class EntityPhysicsCollisionManager {
    /** Hard per-level entity scan budget; keeps a pathological entity count bounded. */
    private static final int MAX_ENTITIES_PER_LEVEL = 2048;
    /** Only the nearest structures are resolved against a single entity. */
    private static final int MAX_STRUCTURES_PER_ENTITY = 8;
    /** Narrow-phase budget per structure, per pass, per entity. */
    private static final int MAX_BOXES_PER_STRUCTURE = 256;
    /** Iterations for the (rare) case where one merged box pushes into another. */
    private static final int MAX_PASSES_PER_STRUCTURE = 3;
    /** Corrections smaller than this are ignored to avoid resting-contact jitter. */
    private static final double MIN_OFFSET_SQUARED = 1.0e-8;
    /** Safety valve: never teleport an entity farther than this in one tick. */
    private static final double MAX_TOTAL_OFFSET_SQUARED = 64.0;
    private static final double MAX_MARGIN = 0.5;
    /** Penetrations at or below this are resting contact, not a collision. */
    private static final double CONTACT_SLOP = 2.0e-3;

    /**
     * A player correction below this is owned by the client prediction layer.
     * The server only teleports when the client cannot have applied it (no
     * prediction channel, or a gross penetration beyond the client's budget).
     * Teleporting every contact tick is what used to zero the player's
     * velocity and make walls feel sticky.
     */
    static final double PLAYER_PREDICTION_TELEPORT_THRESHOLD = 0.5;

    private static volatile Predicate<Entity> entityFilter = entity -> true;
    private static volatile TickStats lastStats = new TickStats(0, 0, 0, 0, 0L);
    private static volatile boolean registered;

    private EntityPhysicsCollisionManager() {
    }

    /**
     * Subscribes the manager to the end of the server tick. Register this
     * after {@code MinecraftPhysics::tick} so the correction uses the pose
     * produced by the current physics step.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(EntityPhysicsCollisionManager::onEndServerTick);
    }

    /**
     * Installs an optional allow/deny predicate. Returning {@code false} from
     * the predicate skips that entity entirely. Base exclusions (spectators,
     * passengers, dead/removed entities, display entities, {@code noPhysics})
     * always apply.
     *
     * @param filter predicate, or {@code null} to allow all eligible entities
     */
    public static void setEntityFilter(Predicate<Entity> filter) {
        entityFilter = filter == null ? entity -> true : filter;
    }

    /**
     * Diagnostics from the most recent tick. Safe to read from any thread.
     */
    public static TickStats lastStats() {
        return lastStats;
    }

    /** Effective margin used by the tick and by the diagnostics. */
    public static double effectiveMargin() {
        return Math.clamp(MinecraftPhysics.config().entityCollisionMargin, 0.0, MAX_MARGIN);
    }

    /**
     * Full collision snapshot for one entity, used by
     * {@code /crythst entitydebug}. Runs regardless of the config switch so a
     * disabled feature can still be inspected.
     */
    public static DebugReport inspect(ServerLevel level, Entity entity) {
        final double margin = effectiveMargin();
        final Aabb entityBox = coreAabb(entity.getBoundingBox());
        final Aabb query = entityBox.expand(margin);
        final Vector3d center = entityBox.center();
        final List<StructureDebug> structures = new ArrayList<>();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics != null && !physics.closed()) {
            for (PhysicsStructure structure : physics.structures()) {
                if (structure.closed()) {
                    continue;
                }
                if (structures.size() >= MAX_STRUCTURES_PER_ENTITY) {
                    break;
                }
                final VoxelColliderData collider = structure.collider();
                if (collider.isEmpty()) {
                    continue;
                }
                final Transform3d pose = structure.pose();
                final Aabb bounds = structure.aabb();
                if (!bounds.expand(1.0).intersects(query)) {
                    continue;
                }
                final Aabb localQuery = EntityStructureSolver.localQueryBox(
                        pose, entityBox.expand(margin), structure.blockScale());
                final Vec3 motion = entity.getDeltaMovement();
                final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                        entityBox, previousCenter(entity, center), pose, collider.boxes(),
                        structure.blockScale(),
                        EntityStructureSolver.Policy.gameplay(margin, MAX_BOXES_PER_STRUCTURE,
                                CONTACT_SLOP, entity.onGround() || motion.y <= 0.0,
                                motion.y));
                final boolean centerInside = structure.obb().contains(center);
                structures.add(new StructureDebug(structure.body().id(), pose, bounds,
                        localQuery, hit, centerInside));
            }
        }
        return new DebugReport(entity.getUUID().toString(), entity.getType().toString(),
                new Vector3d(entity.getX(), entity.getY(), entity.getZ()), entityBox, structures);
    }

    /**
     * Inspects the nearest entities to a point. Intended for diagnostics.
     */
    public static List<DebugReport> inspectNear(ServerLevel level, Vector3d center,
                                                double radius, int maxEntities) {
        final List<DebugReport> reports = new ArrayList<>();
        if (maxEntities <= 0) {
            return reports;
        }
        final double radiusSquared = Math.max(0.0, radius) * Math.max(0.0, radius);
        for (Entity entity : level.getAllEntities()) {
            if (entity.isRemoved()) {
                continue;
            }
            final Vector3d position = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
            if (position.distanceSquared(center) > radiusSquared) {
                continue;
            }
            reports.add(inspect(level, entity));
            if (reports.size() >= maxEntities) {
                break;
            }
        }
        return reports;
    }

    /**
     * Fabric callback: resolve every level with at least one physics structure.
     */
    public static void onEndServerTick(MinecraftServer server) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (!config.entityPhysicsCollisionEnabled || !MinecraftPhysics.available()) {
            return;
        }
        final long start = System.nanoTime();
        final double margin = effectiveMargin();
        int levelCount = 0;
        int structureCount = 0;
        int entityCount = 0;
        int adjustedCount = 0;

        for (ServerLevel level : server.getAllLevels()) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics == null || physics.closed()) {
                continue;
            }
            final List<StructureView> structures = snapshot(physics);
            if (structures.isEmpty()) {
                continue;
            }
            levelCount++;
            structureCount += structures.size();
            int scannedInLevel = 0;
            for (Entity entity : level.getAllEntities()) {
                if (scannedInLevel >= MAX_ENTITIES_PER_LEVEL) {
                    break;
                }
                scannedInLevel++;
                if (!isEligible(entity)) {
                    continue;
                }
                entityCount++;
                if (resolveEntity(entity, structures, margin)) {
                    adjustedCount++;
                }
            }
        }

        lastStats = new TickStats(levelCount, structureCount, entityCount, adjustedCount,
                System.nanoTime() - start);
    }

    private static List<StructureView> snapshot(LevelPhysics physics) {
        final List<PhysicsStructure> structures = physics.structures();
        if (structures.isEmpty()) {
            return List.of();
        }
        final List<StructureView> views = new ArrayList<>(structures.size());
        for (PhysicsStructure structure : structures) {
            if (structure.closed()) {
                continue;
            }
            final VoxelColliderData collider = structure.collider();
            if (collider.isEmpty()) {
                continue;
            }
            // pose() maps local voxel metres to world space and already
            // accounts for the centre-of-mass offset. Bounds come from the
            // body's Bullet AABB, which is authoritative for rotated poses.
            final Transform3d pose = structure.pose();
            final Aabb bounds = structure.aabb();
            views.add(new StructureView(pose, bounds, bounds.center(), collider.boxes(),
                    structure.blockScale()));
        }
        return views;
    }

    private static boolean isEligible(Entity entity) {
        if (entity.isRemoved()
                || !entity.isAlive()
                || entity.isSpectator()
                || entity.isPassenger()
                || entity.noPhysics
                || entity instanceof Display
                || entity instanceof EnderDragonPart) {
            return false;
        }
        final Predicate<Entity> filter = entityFilter;
        return filter == null || filter.test(entity);
    }

    private static boolean resolveEntity(Entity entity, List<StructureView> structures, double margin) {
        final Aabb entityBox = coreAabb(entity.getBoundingBox());
        final Aabb query = entityBox.expand(margin);
        final Vector3d entityCenter = entityBox.center();
        final Vector3d previousCenter = previousCenter(entity, entityCenter);

        List<StructureView> candidates = null;
        for (StructureView structure : structures) {
            if (!structure.bounds().intersects(query)) {
                continue;
            }
            if (candidates == null) {
                candidates = new ArrayList<>(4);
            }
            candidates.add(structure);
        }
        if (candidates == null) {
            return false;
        }
        if (candidates.size() > MAX_STRUCTURES_PER_ENTITY) {
            candidates.sort(Comparator.comparingDouble(
                    candidate -> candidate.center().distanceSquared(entityCenter)));
            candidates.subList(MAX_STRUCTURES_PER_ENTITY, candidates.size()).clear();
        }

        final Vec3 velocity = entity.getDeltaMovement();
        final boolean restingOrDescending = entity.onGround() || velocity.y <= 0.0;
        final EntityStructureSolver.Policy policy = EntityStructureSolver.Policy.gameplay(
                margin, MAX_BOXES_PER_STRUCTURE, CONTACT_SLOP, restingOrDescending, velocity.y);

        final Vector3d totalOffset = new Vector3d();
        final Vector3d lastNormal = new Vector3d();
        final List<Vector3d> contacts = new ArrayList<>(4);
        double lastPenetration = 0.0;
        boolean lastSupport = false;
        boolean touched = false;
        for (StructureView structure : candidates) {
            for (int pass = 0; pass < MAX_PASSES_PER_STRUCTURE; pass++) {
                final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                        entityBox.offset(totalOffset), previousCenter, structure.pose(),
                        structure.boxes(), structure.blockScale(), policy);
                if (hit == null) {
                    break;
                }
                totalOffset.add(hit.offset());
                lastNormal.set(hit.normal());
                lastPenetration = hit.penetration();
                addContact(contacts, hit.normal());
                // A landing pass can be followed by a wall pass; keep the
                // support flag so the caller still marks the contact as ground.
                lastSupport |= hit.support();
                touched = true;
                if (totalOffset.lengthSquared() > MAX_TOTAL_OFFSET_SQUARED) {
                    break;
                }
            }
            if (totalOffset.lengthSquared() > MAX_TOTAL_OFFSET_SQUARED) {
                break;
            }
        }

        if (!touched || totalOffset.lengthSquared() < MIN_OFFSET_SQUARED) {
            return false;
        }
        applyCorrection(entity, totalOffset, lastNormal, contacts,
                lastPenetration, lastSupport);
        return true;
    }

    private static void addContact(List<Vector3d> contacts, Vector3d normal) {
        for (Vector3d existing : contacts) {
            if (existing.dot(normal) > 0.9999) {
                return;
            }
        }
        contacts.add(new Vector3d(normal));
    }

    private static void applyCorrection(Entity entity, Vector3d offset, Vector3d normal,
                                        List<Vector3d> contacts,
                                        double penetration, boolean support) {
        final Vec3 position = entity.position();
        final Vec3 velocityBefore = entity.getDeltaMovement();
        final double x = position.x + offset.x;
        final double y = position.y + offset.y;
        final double z = position.z + offset.z;

        if (entity instanceof ServerPlayer player && player.connection != null) {
            final boolean predictionOwnsIt = !shouldTeleportPlayer(
                    hasClientPrediction(player), offset.length());
            if (predictionOwnsIt) {
                // The client already corrected its own copy this tick; its
                // next movement packet carries that position. Teleporting here
                // would overwrite the prediction and zero the player's
                // velocity every contact tick (the "glued to the wall" feel).
                if (support || normal.y > 0.5) {
                    player.setOnGround(true);
                    player.resetFallDistance();
                }
                StructureCollisionDebug.serverPlayer("-", penetration,
                        normal, offset, velocityBefore, velocityBefore, false,
                        "client-prediction");
                return;
            }
            // No prediction channel (vanilla client) or a gross penetration:
            // the server is authoritative and must reposition the player.
            // Carry the tangential velocity through the teleport so a single
            // fallback correction does not stop the player dead.
            final Vec3 motion = adjustVelocity(velocityBefore, contacts);
            player.connection.teleport(new PositionMoveRotation(
                    new Vec3(x, y, z), motion, player.getYRot(), player.getXRot()), Set.of());
            player.setDeltaMovement(motion);
            if (support || normal.y > 0.5) {
                player.setOnGround(true);
                player.resetFallDistance();
            }
            StructureCollisionDebug.serverPlayer("-", penetration,
                    normal, offset, velocityBefore, motion, true, "teleport");
            return;
        }

        final Vec3 motion = adjustVelocity(velocityBefore, contacts);
        entity.setPos(x, y, z);
        entity.setDeltaMovement(motion);
        if (support || normal.y > 0.5) {
            // The structure is not a vanilla block, so help mob AI and fall
            // damage by marking the contact as ground even though vanilla
            // terrain does not know about it.
            entity.setOnGround(true);
            entity.resetFallDistance();
        }
        StructureCollisionDebug.serverEntity("-", penetration,
                normal, offset, velocityBefore, motion, true, support ? "support" : "slide");
    }

    /**
     * Whether a player correction must be sent as an authoritative teleport.
     * With a prediction-capable client, ordinary contact is owned by the
     * client; only a gross correction is forced. Visible for unit tests.
     */
    static boolean shouldTeleportPlayer(boolean clientPrediction, double offsetLength) {
        return !clientPrediction || offsetLength > PLAYER_PREDICTION_TELEPORT_THRESHOLD;
    }

    /**
     * Whether this player's client runs the Crythst prediction layer. The
     * integrated client always does; a remote client advertises the structure
     * sync channel. Query failures are treated as "no prediction" so the
     * server stays conservative.
     */
    private static boolean hasClientPrediction(ServerPlayer player) {
        final MinecraftServer server = player.level().getServer();
        if (server != null && server.isSingleplayer()) {
            return true;
        }
        try {
            return ServerPlayNetworking.canSend(player, StructureSnapshotPayload.TYPE);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Removes the velocity component that points into each contact surface.
     * Every pass normal is used, not just the last one, so a floor+wall pair
     * cannot accumulate downward velocity behind the wall correction. All
     * tangential components are preserved, which is what lets a player slide
     * along a wall or jump away from a floor.
     */
    private static Vec3 adjustVelocity(Vec3 motion, List<Vector3d> contacts) {
        Vec3 result = motion;
        for (Vector3d normal : contacts) {
            final double into = result.x * normal.x + result.y * normal.y + result.z * normal.z;
            if (into < 0.0) {
                result = new Vec3(
                        result.x - normal.x * into,
                        result.y - normal.y * into,
                        result.z - normal.z * into
                );
            }
        }
        return result;
    }

    /**
     * The current AABB centre translated back to the previous tick's position.
     * Entity AABBs are anchored to the entity position, so a translation is
     * enough even when the bounding box changes with pose.
     */
    private static Vector3d previousCenter(Entity entity, Vector3d currentCenter) {
        final Vec3 previous = entity.oldPosition();
        final Vec3 position = entity.position();
        return new Vector3d(
                currentCenter.x + previous.x - position.x,
                currentCenter.y + previous.y - position.y,
                currentCenter.z + previous.z - position.z
        );
    }

    private static Aabb coreAabb(AABB box) {
        return new Aabb(
                new Vector3d(box.minX, box.minY, box.minZ),
                new Vector3d(box.maxX, box.maxY, box.maxZ)
        );
    }

    /**
     * Per-tick diagnostics for logs and debug commands.
     */
    public record TickStats(int levels, int structures, int entities, int adjusted, long nanos) {
        public boolean changedAnything() {
            return adjusted > 0;
        }
    }

    /**
     * One entity's diagnostic snapshot from {@link #inspect}.
     */
    public record DebugReport(
            String entityUuid,
            String entityType,
            Vector3d position,
            Aabb entityBox,
            List<StructureDebug> structures
    ) {
    }

    /**
     * One structure's diagnostic snapshot: the pose used for collision, its
     * broad-phase AABB, the local voxel query box, and the MTV chosen by the
     * solver. {@code centerInside} reports whether the entity centre is inside
     * the structure OBB.
     */
    public record StructureDebug(
            long bodyId,
            Transform3d pose,
            Aabb bounds,
            Aabb localQuery,
            EntityStructureSolver.Hit hit,
            boolean centerInside
    ) {
    }

    private record StructureView(
            Transform3d pose,
            Aabb bounds,
            Vector3d center,
            List<VoxelBox> boxes,
            double blockScale
    ) {
    }
}
