package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side structure source used by movement collision.
 *
 * <p>Two data sources are merged:</p>
 * <ul>
 *   <li>singleplayer: live {@link PhysicsStructure} instances exposed by
 *       {@link ClientPhysicsView}. Collision samples
 *       {@code renderStateAt(now + timelineDelay)}, which returns the newest
 *       published server pose instead of the one-tick-delayed render pose.
 *       Reading the timeline is safe from the client thread.</li>
 *   <li>multiplayer: {@link ClientStructureCache} snapshots, using each
 *       snapshot's last synced pose and merged boxes rebuilt from its solid
 *       cells. Bounds and geometry are cached per geometry revision.</li>
 * </ul>
 *
 * <p>The whole list is rebuilt at most once per client game tick, so every
 * entity moving in the same tick resolves against the same surfaces. The class
 * is client-only and is only class-loaded through a guarded holder.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientStructureCollisionSource implements StructureCollisionSource {
    private static final double CLIENT_MAX_CORRECTION = 1.0;
    private static final double NETWORK_GEOMETRY_PADDING = 0.001;

    private static long snapshotGameTime = Long.MIN_VALUE;
    private static Identifier snapshotDimension;
    private static long snapshotSyncId = Long.MIN_VALUE;
    private static List<StructureCollisionBox> snapshot = List.of();
    private static final Map<Long, CachedGeometry> NETWORK_GEOMETRY = new HashMap<>();

    private final Identifier dimension;
    private final Level level;

    private ClientStructureCollisionSource(Level level) {
        this.level = level;
        this.dimension = level.dimension().identifier();
    }

    /** Creates a source bound to one level. */
    public static StructureCollisionSource of(Level level) {
        if (level == null) {
            return EmptySource.INSTANCE;
        }
        return new ClientStructureCollisionSource(level);
    }

    @Override
    public List<StructureCollisionBox> candidates(Aabb query) {
        final List<StructureCollisionBox> current = currentStructures();
        if (current.isEmpty()) {
            return List.of();
        }
        final List<StructureCollisionBox> found = new ArrayList<>(4);
        for (StructureCollisionBox structure : current) {
            if (structure.worldBounds().intersects(query)) {
                found.add(structure);
            }
        }
        return found;
    }

    @Override
    public double maxCorrection() {
        return CLIENT_MAX_CORRECTION;
    }

    @Override
    public String side() {
        return "client";
    }

    private List<StructureCollisionBox> currentStructures() {
        final long gameTime = level.getGameTime();
        final long syncId = ClientStructureCache.activeSyncId();
        if (gameTime == snapshotGameTime && syncId == snapshotSyncId
                && dimension.equals(snapshotDimension)) {
            return snapshot;
        }

        final long now = System.nanoTime();
        final List<StructureCollisionBox> next = new ArrayList<>(4);
        final Set<Long> liveBodyIds = new HashSet<>();

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final VoxelColliderData collider = structure.collider();
            if (collider.isEmpty()) {
                continue;
            }
            final StructureRenderState state = structure.renderStateAt(
                    now + structure.renderTimelineDelayNanos());
            liveBodyIds.add(state.bodyId());
            if (collider.boxes().isEmpty()) {
                continue;
            }
            next.add(new StructureCollisionBox(
                    state.bodyId(),
                    state.pose(),
                    collider.boxes(),
                    state.blockScale(),
                    state.aabb()
            ));
        }

        final Identifier active = ClientStructureCache.activeDimension();
        if (active != null && active.equals(dimension)) {
            for (ClientStructureSnapshot structure : ClientStructureCache.snapshots()) {
                if (structure.nonAirBlocks() <= 0
                        || liveBodyIds.contains(structure.bodyId())) {
                    continue;
                }
                final Aabb bounds = networkBounds(structure);
                final List<VoxelBox> boxes = networkBoxes(structure, syncId);
                if (boxes.isEmpty()) {
                    continue;
                }
                next.add(new StructureCollisionBox(
                        structure.bodyId(),
                        structure.pose(),
                        boxes,
                        structure.blockScale(),
                        bounds.expand(NETWORK_GEOMETRY_PADDING)
                ));
            }
        }

        snapshot = List.copyOf(next);
        snapshotGameTime = gameTime;
        snapshotSyncId = syncId;
        snapshotDimension = dimension;
        return snapshot;
    }

    private static List<VoxelBox> networkBoxes(ClientStructureSnapshot structure, long syncId) {
        if (syncId != snapshotSyncId) {
            NETWORK_GEOMETRY.clear();
        }
        final CachedGeometry cached = NETWORK_GEOMETRY.get(structure.bodyId());
        if (cached != null && cached.geometryRevision() == structure.geometryRevision()) {
            return cached.boxes();
        }
        final VoxelColliderData data = VoxelColliderBuilder.build(
                new SnapshotVolume(structure),
                VoxelColliderOptions.DEFAULT.withBlockScale(structure.blockScale()));
        NETWORK_GEOMETRY.put(structure.bodyId(),
                new CachedGeometry(structure.geometryRevision(), data.boxes()));
        return data.boxes();
    }

    /**
     * World-space AABB of a network snapshot. Corners are transformed
     * explicitly so broad-phase bounds and rendered geometry cannot diverge.
     */
    private static Aabb networkBounds(ClientStructureSnapshot structure) {
        final IntBounds3 bounds = structure.bounds();
        final Transform3d pose = structure.pose();
        final double scale = structure.blockScale();
        final double localMinX = bounds.minX() * scale;
        final double localMinY = bounds.minY() * scale;
        final double localMinZ = bounds.minZ() * scale;
        final double localMaxX = bounds.maxX() * scale;
        final double localMaxY = bounds.maxY() * scale;
        final double localMaxZ = bounds.maxZ() * scale;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d point = pose.transformPoint(new Vector3d(
                    (corner & 1) == 0 ? localMinX : localMaxX,
                    (corner & 2) == 0 ? localMinY : localMaxY,
                    (corner & 4) == 0 ? localMinZ : localMaxZ
            ));
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        return new Aabb(new Vector3d(minX, minY, minZ),
                new Vector3d(maxX, maxY, maxZ));
    }

    private record CachedGeometry(long geometryRevision, List<VoxelBox> boxes) {
    }

    /** Adapts a network snapshot to the collider builder's solid-cell view. */
    private record SnapshotVolume(ClientStructureSnapshot snapshot) implements VoxelVolume {
        @Override
        public IntBounds3 bounds() {
            return snapshot.bounds();
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return snapshot.isSolid(x, y, z);
        }
    }

    private enum EmptySource implements StructureCollisionSource {
        INSTANCE;

        @Override
        public List<StructureCollisionBox> candidates(Aabb query) {
            return List.of();
        }

        @Override
        public double maxCorrection() {
            return 0.0;
        }

        @Override
        public String side() {
            return "client";
        }
    }
}
