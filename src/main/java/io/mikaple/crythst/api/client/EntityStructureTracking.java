package io.mikaple.crythst.api.client;

import io.mikaple.crythst.integration.minecraft.collision.StructureCollisionTrace;
import io.mikaple.crythst.math.Aabb;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-safe tracking structure and inherited-velocity queries.
 *
 * <p>The authoritative movement collision only exposes contact normals and
 * body ids internally; it does not publish a per-entity tracking structure to
 * client code. This class therefore keeps a small, client-thread tracker:</p>
 *
 * <ol>
 *   <li>An integration that already has a contact can call
 *       {@link #track(Entity, StructureRenderView)} explicitly.</li>
 *   <li>Otherwise the latest {@link StructureCollisionTrace} sample is used as
 *       a hint when it is recent, from the client side, has the same entity
 *       type and the hinted structure actually overlaps the entity. The trace
 *       is process-global, so the geometric check is mandatory.</li>
 *   <li>If neither is available, the structure whose render AABB is nearest
 *       to the entity's feet is used.</li>
 * </ol>
 *
 * <p>Inherited velocity is a finite difference of the timestamped render pose
 * pair, including the angular term. It never reads a native body, so it works
 * for both singleplayer and network structures. The value is in metres per
 * second, matching {@code EntityStructureUtil#getVelocity} on the server.</p>
 *
 * <p>This type is client-only and must not be loaded on a dedicated server.</p>
 */
public final class EntityStructureTracking {
    /** A hint older than this is considered stale. */
    private static final long HINT_NANOS = 150_000_000L;
    /** Extra distance around the entity box used when matching structures. */
    private static final double MATCH_SLOP = 0.75;

    private static final Map<Entity, Tracked> TRACKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private EntityStructureTracking() {
    }

    /**
     * The structure carrying or supporting {@code entity}, or {@code null}
     * when the entity is not near a structure.
     */
    public static @Nullable StructureRenderView getTrackingStructure(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return null;
        }
        final long now = System.nanoTime();
        final Tracked cached = TRACKED.get(entity);
        if (cached != null && now - cached.nanos() <= HINT_NANOS) {
            final StructureRenderView view = StructureRenderView.get(cached.bodyId());
            if (view != null && near(entity, view, MATCH_SLOP)) {
                return view;
            }
        }

        final StructureRenderView hinted = fromCollisionTrace(entity, now);
        final StructureRenderView tracking = hinted != null ? hinted : infer(entity);
        if (tracking == null) {
            TRACKED.remove(entity);
        } else {
            TRACKED.put(entity, new Tracked(tracking.bodyId(), now));
        }
        return tracking;
    }

    /**
     * Inherited material velocity at the entity position, in metres per
     * second. Returns zero when no tracking structure is found.
     */
    public static Vector3d getInheritedVelocity(Entity entity) {
        final StructureRenderView structure = getTrackingStructure(entity);
        if (structure == null) {
            return new Vector3d();
        }
        final Vec3 position = entity.position();
        return structure.velocityAt(new Vector3d(position.x, position.y, position.z));
    }

    /**
     * Explicitly records the tracking structure. Integrations that observe a
     * movement collision should call this once per contact; it is cheaper and
     * more precise than the heuristic fallback.
     */
    public static void track(Entity entity, StructureRenderView structure) {
        if (entity == null) {
            return;
        }
        if (structure == null) {
            TRACKED.remove(entity);
            return;
        }
        TRACKED.put(entity, new Tracked(structure.bodyId(), System.nanoTime()));
    }

    /** Explicitly records a tracking structure by body id. */
    public static void track(Entity entity, long bodyId) {
        if (entity == null) {
            return;
        }
        TRACKED.put(entity, new Tracked(bodyId, System.nanoTime()));
    }

    /** Drops the cached tracking structure for an entity. */
    public static void clear(Entity entity) {
        if (entity != null) {
            TRACKED.remove(entity);
        }
    }

    private static @Nullable StructureRenderView fromCollisionTrace(Entity entity, long now) {
        final StructureCollisionTrace.Sample sample = StructureCollisionTrace.last();
        if (sample == null || !"client".equals(sample.side())
                || now - sample.nanos() > HINT_NANOS
                || !sample.entityType().equals(entity.getType().toString())) {
            return null;
        }
        for (String id : sample.bodyIds().split(",")) {
            try {
                final StructureRenderView view = StructureRenderView.get(Long.parseLong(id.trim()));
                if (view != null && near(entity, view, MATCH_SLOP)) {
                    return view;
                }
            } catch (NumberFormatException ignored) {
                // "-" or a malformed diagnostic value; fall back to geometry.
            }
        }
        return null;
    }

    private static @Nullable StructureRenderView infer(Entity entity) {
        final AABB box = entity.getBoundingBox();
        final Aabb search = new Aabb(
                new Vector3d(box.minX - MATCH_SLOP, box.minY - MATCH_SLOP, box.minZ - MATCH_SLOP),
                new Vector3d(box.maxX + MATCH_SLOP, box.maxY + MATCH_SLOP, box.maxZ + MATCH_SLOP));
        final double feetY = box.minY;
        final double centerX = (box.minX + box.maxX) * 0.5;
        final double centerZ = (box.minZ + box.maxZ) * 0.5;

        StructureRenderView best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (StructureRenderView view : StructureRenderView.all()) {
            final Aabb bounds = view.aabb();
            if (!bounds.intersects(search)) {
                continue;
            }
            final double vertical = Math.abs(bounds.max().y - feetY);
            final double dx = centerX - (bounds.min().x + bounds.max().x) * 0.5;
            final double dz = centerZ - (bounds.min().z + bounds.max().z) * 0.5;
            final double score = vertical + Math.sqrt(dx * dx + dz * dz) * 0.25;
            if (score < bestScore) {
                bestScore = score;
                best = view;
            }
        }
        return best;
    }

    private static boolean near(Entity entity, StructureRenderView structure, double slop) {
        final AABB box = entity.getBoundingBox();
        final Aabb expanded = new Aabb(
                new Vector3d(box.minX - slop, box.minY - slop, box.minZ - slop),
                new Vector3d(box.maxX + slop, box.maxY + slop, box.maxZ + slop));
        return structure.aabb().intersects(expanded);
    }

    private record Tracked(long bodyId, long nanos) {
    }
}
