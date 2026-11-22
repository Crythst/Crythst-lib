package io.mikaple.crythst.api.client;

import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.client.network.ClientBlockEntity;
import io.mikaple.crythst.client.network.ClientBlockEntitySnapshot;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only client-side structure container.
 *
 * <p>Two client data sources are exposed:</p>
 * <ul>
 *   <li>{@link ClientPhysicsView} in singleplayer exposes the integrated
 *       server's published {@link PhysicsStructure} render snapshots. The
 *       {@link StructureContainer} methods operate on those live objects.</li>
 *   <li>{@link ClientStructureCache} in multiplayer exposes immutable
 *       {@link ClientStructureSnapshot}s. There is no client-side
 *       {@code PhysicsStructure} for those bodies, so
 *       {@link #structures()} stays empty; use {@link #snapshots()} and
 *       {@link #snapshot(long)} instead.</li>
 * </ul>
 *
 * <p>This class is client-only and is reached through
 * {@code StructureContainerClientHolder}; a dedicated server never loads it.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientStructureContainer implements StructureContainer {
    private final Level level;

    private ClientStructureContainer(Level level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    /**
     * Creates a read-only view for a client level. A new lightweight wrapper is
     * returned per call; the underlying client caches are static and shared.
     */
    public static ClientStructureContainer get(Level level) {
        return new ClientStructureContainer(level);
    }

    @Override
    public Level level() {
        return level;
    }

    /**
     * Live render structures published by the integrated server, or an empty
     * list in multiplayer.
     */
    @Override
    public List<PhysicsStructure> structures() {
        final List<PhysicsStructure> live = ClientPhysicsView.structures();
        if (live.isEmpty()) {
            return List.of();
        }
        final List<PhysicsStructure> result = new ArrayList<>(live.size());
        for (PhysicsStructure structure : live) {
            if (!structure.closed()) {
                result.add(structure);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Structures synced to this client, or an empty list when the cache belongs
     * to another dimension.
     */
    public List<ClientStructureSnapshot> snapshots() {
        final Identifier active = ClientStructureCache.activeDimension();
        if (active != null && !active.equals(level.dimension().identifier())) {
            return List.of();
        }
        return List.copyOf(ClientStructureCache.snapshots());
    }

    /**
     * Synced structure snapshot by body id, or {@code null}.
     */
    public @Nullable ClientStructureSnapshot snapshot(long bodyId) {
        final Identifier active = ClientStructureCache.activeDimension();
        if (active != null && !active.equals(level.dimension().identifier())) {
            return null;
        }
        return ClientStructureCache.get(bodyId);
    }

    /**
     * Immutable block-entity NBT/animation state for a synced body, or
     * {@code null} when the body has no block entities (or is not synced).
     *
     * <p>Coordinates in the returned snapshot are structure-local voxels,
     * relative to {@link ClientStructureSnapshot#bounds()} minimum. The data is
     * immutable and safe to read from the render thread.</p>
     */
    public @Nullable ClientBlockEntitySnapshot blockEntities(long bodyId) {
        final Identifier active = ClientStructureCache.activeDimension();
        if (active != null && !active.equals(level.dimension().identifier())) {
            return null;
        }
        return ClientStructureCache.blockEntities(bodyId);
    }

    /**
     * One synced block entity at a structure-local voxel, or {@code null}.
     */
    public @Nullable ClientBlockEntity blockEntityAt(
            long bodyId, int x, int y, int z) {
        final Identifier active = ClientStructureCache.activeDimension();
        if (active != null && !active.equals(level.dimension().identifier())) {
            return null;
        }
        return ClientStructureCache.blockEntityAt(bodyId, x, y, z);
    }

    /**
     * Synced snapshot whose solid cells contain a world-space point, or
     * {@code null}.
     *
     * <p>This is the multiplayer counterpart of
     * {@link StructureContainer#containing(Vec3)}. It never reads a native
     * physics body; containment is computed from the immutable snapshot pose
     * and cell palette.</p>
     */
    public @Nullable ClientStructureSnapshot snapshotContaining(Vec3 point) {
        if (point == null) {
            return null;
        }
        final Vector3d query = new Vector3d(point.x, point.y, point.z);
        for (ClientStructureSnapshot snapshot : snapshots()) {
            final Vector3d local = snapshot.pose().inverseTransformPoint(query)
                    .mul(1.0 / snapshot.blockScale());
            final int x = (int) Math.floor(local.x);
            final int y = (int) Math.floor(local.y);
            final int z = (int) Math.floor(local.z);
            if (snapshot.bounds().contains(x, y, z) && snapshot.isSolid(x, y, z)) {
                return snapshot;
            }
        }
        return null;
    }

    /**
     * Synced snapshots whose projected world AABB overlaps {@code box}.
     */
    public List<ClientStructureSnapshot> snapshotsIntersecting(Aabb box) {
        if (box == null) {
            throw new NullPointerException("box");
        }
        final List<ClientStructureSnapshot> result = new ArrayList<>();
        for (ClientStructureSnapshot snapshot : snapshots()) {
            if (worldAabb(snapshot).intersects(box)) {
                result.add(snapshot);
            }
        }
        return List.copyOf(result);
    }

    private static Aabb worldAabb(ClientStructureSnapshot snapshot) {
        final var bounds = snapshot.bounds();
        final double scale = snapshot.blockScale();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final double x = ((i & 1) == 0 ? bounds.minX() : bounds.maxX()) * scale;
            final double y = ((i & 2) == 0 ? bounds.minY() : bounds.maxY()) * scale;
            final double z = ((i & 4) == 0 ? bounds.minZ() : bounds.maxZ()) * scale;
            final Vector3d world = snapshot.pose().transformPoint(new Vector3d(x, y, z));
            minX = Math.min(minX, world.x);
            minY = Math.min(minY, world.y);
            minZ = Math.min(minZ, world.z);
            maxX = Math.max(maxX, world.x);
            maxY = Math.max(maxY, world.y);
            maxZ = Math.max(maxZ, world.z);
        }
        return Aabb.of(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    /**
     * Number of structures synced from the server. May be non-zero in
     * multiplayer while {@link #structures()} is empty.
     */
    public int snapshotCount() {
        return snapshots().size();
    }

    /**
     * Whether the server-synced client cache has no structures for this
     * dimension.
     */
    public boolean networkIsEmpty() {
        return snapshots().isEmpty();
    }
}
