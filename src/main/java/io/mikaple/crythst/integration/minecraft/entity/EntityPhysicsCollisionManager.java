package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.collision.StructureCollisionSettings;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Read-only structure collision diagnostics.
 *
 * <p>The old end-of-tick correction pass was removed: collision now runs
 * inside vanilla {@code Entity.move} through
 * {@link io.mikaple.crythst.integration.minecraft.collision.EntityStructureCollision}.
 * This class is kept as the diagnostic entry point used by
 * {@code /crythst entitydebug}; {@link #register()} and
 * {@link #onEndServerTick(MinecraftServer)} are deliberately no-ops so an old
 * registration call cannot resurrect post-tick teleports.</p>
 */
public final class EntityPhysicsCollisionManager {
    /** Hard per-level entity scan budget for the inspector. */
    private static final int MAX_ENTITIES_PER_LEVEL = 2048;
    /** Only the nearest structures are resolved against a single entity. */
    private static final int MAX_STRUCTURES_PER_ENTITY = 8;
    /** Narrow-phase budget per structure, per pass, per entity. */
    private static final int MAX_BOXES_PER_STRUCTURE = 256;
    /** Corrections smaller than this are ignored to avoid resting-contact jitter. */
    private static final double CONTACT_SLOP = 2.0e-3;
    private static final double MAX_MARGIN = 0.5;
    /**
     * Kept for source compatibility with the old authority test. The movement
     * path never sends a contact teleport, so this value is not consulted.
     */
    static final double PLAYER_PREDICTION_TELEPORT_THRESHOLD = 0.5;

    private static volatile TickStats lastStats = new TickStats(0, 0, 0, 0, 0L);

    private EntityPhysicsCollisionManager() {
    }

    /**
     * No-op. The movement-integrated path is always active; there is no
     * end-of-tick correction to subscribe.
     */
    public static void register() {
    }

    /**
     * Installs the shared allow/deny predicate. It is consulted by the
     * movement collision path as well as by {@link #inspect}.
     *
     * @param filter predicate, or {@code null} to allow all eligible entities
     */
    public static void setEntityFilter(Predicate<Entity> filter) {
        StructureCollisionSettings.setEntityFilter(filter);
    }

    /**
     * Diagnostics from the most recent tick. The post-tick pass no longer
     * adjusts entities, so the counters stay at zero.
     */
    public static TickStats lastStats() {
        return lastStats;
    }

    /** Effective margin used by the diagnostics. */
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
                        collider.customParts(), structure.blockScale(),
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

    /** Inspects the nearest entities to a point. Intended for diagnostics. */
    public static List<DebugReport> inspectNear(ServerLevel level, Vector3d center,
                                                double radius, int maxEntities) {
        final List<DebugReport> reports = new ArrayList<>();
        if (maxEntities <= 0) {
            return reports;
        }
        final double radiusSquared = Math.max(0.0, radius) * Math.max(0.0, radius);
        int scanned = 0;
        for (Entity entity : level.getAllEntities()) {
            if (scanned++ >= MAX_ENTITIES_PER_LEVEL) {
                break;
            }
            if (entity.isRemoved() || !StructureCollisionSettings.allows(entity)) {
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
     * Legacy callback. Present so an existing registration compiles; it never
     * moves an entity.
     */
    public static void onEndServerTick(MinecraftServer server) {
        lastStats = new TickStats(0, 0, 0, 0, 0L);
    }

    /**
     * Whether a player correction must be sent as an authoritative teleport.
     * Retained for the historical unit test; the movement path never teleports
     * contacts.
     */
    static boolean shouldTeleportPlayer(boolean clientPrediction, double offsetLength) {
        return !clientPrediction || offsetLength > PLAYER_PREDICTION_TELEPORT_THRESHOLD;
    }

    private static Vector3d previousCenter(Entity entity, Vector3d currentCenter) {
        final Vec3 previous = entity.oldPosition();
        final Vec3 position = entity.position();
        return new Vector3d(
                currentCenter.x + previous.x - position.x,
                currentCenter.y + previous.y - position.y,
                currentCenter.z + previous.z - position.z
        );
    }

    private static Aabb coreAabb(net.minecraft.world.phys.AABB box) {
        return new Aabb(
                new Vector3d(box.minX, box.minY, box.minZ),
                new Vector3d(box.maxX, box.maxY, box.maxZ)
        );
    }

    /** Per-tick diagnostics for logs and debug commands. */
    public record TickStats(int levels, int structures, int entities, int adjusted, long nanos) {
        public boolean changedAnything() {
            return adjusted > 0;
        }
    }

    /** One entity's diagnostic snapshot from {@link #inspect}. */
    public record DebugReport(
            String entityUuid,
            String entityType,
            Vector3d position,
            Aabb entityBox,
            List<StructureDebug> structures
    ) {
    }

    /** One structure's diagnostic snapshot from {@link #inspect}. */
    public record StructureDebug(
            long bodyId,
            Transform3d pose,
            Aabb bounds,
            Aabb localQuery,
            EntityStructureSolver.Hit hit,
            boolean centerInside
    ) {
    }
}
