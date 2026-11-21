package io.mikaple.crythst.api.client;

import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

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
