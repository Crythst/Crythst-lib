package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
                final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                        entityBox, previousCenter(entity, center), pose, collider.boxes(),
                        structure.blockScale(), margin, MAX_BOXES_PER_STRUCTURE);
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

        final Vector3d totalOffset = new Vector3d();
        final Vector3d lastNormal = new Vector3d();
        boolean touched = false;
        for (StructureView structure : candidates) {
            for (int pass = 0; pass < MAX_PASSES_PER_STRUCTURE; pass++) {
                final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                        entityBox.offset(totalOffset), previousCenter, structure.pose(),
                        structure.boxes(), structure.blockScale(), margin,
                        MAX_BOXES_PER_STRUCTURE);
                if (hit == null) {
                    break;
                }
                totalOffset.add(hit.offset());
                lastNormal.set(hit.normal());
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
        applyCorrection(entity, totalOffset, lastNormal);
        return true;
    }

    private static void applyCorrection(Entity entity, Vector3d offset, Vector3d normal) {
        final Vec3 position = entity.position();
        final double x = position.x + offset.x;
        final double y = position.y + offset.y;
        final double z = position.z + offset.z;
        final Vec3 motion = adjustVelocity(entity.getDeltaMovement(), normal);

        if (entity instanceof ServerPlayer player && player.connection != null) {
            // Clients predict vanilla movement, so the server must explicitly
            // reposition the player or the local copy immediately re-enters.
            player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(motion);
        } else {
            entity.setPos(x, y, z);
            entity.setDeltaMovement(motion);
        }

        if (normal.y > 0.5) {
            // The structure is not a vanilla block, so help mob AI and fall
            // damage by marking the contact as ground even though vanilla
            // terrain does not know about it.
            entity.setOnGround(true);
            entity.resetFallDistance();
        }
    }

    /**
     * Removes the velocity component that points into the structure. This
     * stops the next tick from re-penetrating and lets horizontal movement
     * slide along a wall.
     */
    private static Vec3 adjustVelocity(Vec3 motion, Vector3d normal) {
        final double into = motion.x * normal.x + motion.y * normal.y + motion.z * normal.z;
        if (into >= 0.0) {
            return motion;
        }
        return new Vec3(
                motion.x - normal.x * into,
                motion.y - normal.y * into,
                motion.z - normal.z * into
        );
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
