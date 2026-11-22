package io.mikaple.crythst.integration.fabric.network;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructureBlockEntity;
import io.mikaple.crythst.network.structure.StructureBlockEntityDelta;
import io.mikaple.crythst.network.structure.StructureBlockEntityPayload;
import io.mikaple.crythst.network.structure.StructureBlockEntityUpdate;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureRequestPayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.network.structure.StructureSyncDebug;
import io.mikaple.crythst.network.structure.StructureSyncLimits;
import io.mikaple.crythst.network.structure.StructureSyncProtocol;
import io.mikaple.crythst.network.structure.StructureTooLargeException;
import io.mikaple.crythst.network.structure.StructureVoxelPos;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side structure broadcaster.
 *
 * <p>The authoritative structure list lives in {@link LevelPhysics}. This class
 * never mutates it; once per server tick it diffs a cheap fingerprint
 * (pose, volume identity, collider identity) and sends snapshots, removals and
 * batched pose updates to players in the same dimension. Block-entity NBT is
 * captured only when the structure's block-entity revision or a chest opener
 * count changes, and is sent as a minimal dirty set on a separate versioned
 * payload.</p>
 *
 * <p>Every full sync assigns a fresh per-player {@code syncId}. All subsequent
 * pose/removal packets carry that id, so body ids that collide across
 * dimensions (or stale packets from a previous level) cannot corrupt a client
 * view.</p>
 *
 * <p>The tick listener is registered on {@code SERVER_STARTED}, after every
 * mod initializer has registered its own listeners. That guarantees this diff
 * runs after {@code PhysicsTicker}'s end-of-tick physics update and therefore
 * reads the current pose.</p>
 */
public final class StructureSyncServer {
    private static final Map<ResourceKey<Level>, LevelState> LEVEL_STATES = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> PLAYER_LEVELS = new HashMap<>();
    private static final Map<UUID, Long> PLAYER_SYNC_IDS = new HashMap<>();
    private static final Set<UUID> SYNCED_PLAYERS = new HashSet<>();

    private static final AtomicLong NEXT_SYNC_ID = new AtomicLong();
    private static final AtomicLong NEXT_BLOCK_ENTITY_REVISION = new AtomicLong();
    private static final AtomicBoolean TICK_REGISTERED = new AtomicBoolean();

    private static boolean registered;
    private static long sentSnapshotBatches;
    private static long sentSnapshotStructures;
    private static long sentPoseBatches;
    private static long sentPoseUpdates;
    private static long sentRemovalBatches;
    private static long sentRemovals;
    private static long sentBlockEntityPayloads;
    private static long sentBlockEntityUpdates;
    private static long sentBlockEntityEntries;
    private static long skippedBlockEntityEntries;
    private static long skippedBodies;
    private static String lastError = "";

    private StructureSyncServer() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ServerPlayNetworking.registerGlobalReceiver(StructureRequestPayload.TYPE,
                (payload, context) -> requestFullSync(context.player()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.getPlayer()));
        // Registered lazily on SERVER_STARTED. Mod initializers have all run by
        // then, so this END_SERVER_TICK listener is queued after PhysicsTicker.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> registerTickListener());
        ServerLevelEvents.UNLOAD.register((server, level) -> LEVEL_STATES.remove(level.dimension()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    private static void registerTickListener() {
        if (TICK_REGISTERED.compareAndSet(false, true)) {
            ServerTickEvents.END_SERVER_TICK.register(StructureSyncServer::tick);
            StructureSyncDebug.log("server tick listener registered after mod initializers");
        }
    }

    private static void onJoin(ServerPlayer player) {
        if (player == null) {
            return;
        }
        final UUID id = player.getUUID();
        PLAYER_LEVELS.put(id, player.level().dimension());
        if (sendFullSync(player, MinecraftPhysics.getIfPresent(player.level()))) {
            SYNCED_PLAYERS.add(id);
        } else {
            SYNCED_PLAYERS.remove(id);
        }
    }

    private static void onDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        final UUID id = player.getUUID();
        PLAYER_LEVELS.remove(id);
        PLAYER_SYNC_IDS.remove(id);
        SYNCED_PLAYERS.remove(id);
    }

    private static void requestFullSync(ServerPlayer player) {
        if (player == null) {
            return;
        }
        final UUID id = player.getUUID();
        PLAYER_LEVELS.put(id, player.level().dimension());
        if (sendFullSync(player, MinecraftPhysics.getIfPresent(player.level()))) {
            SYNCED_PLAYERS.add(id);
        } else {
            SYNCED_PLAYERS.remove(id);
        }
    }

    private static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            final List<ServerPlayer> players = List.copyOf(level.players());
            final LevelState state = LEVEL_STATES.computeIfAbsent(level.dimension(), key -> new LevelState());

            final List<StructureSnapshot> added = new ArrayList<>();
            final List<Long> removed = new ArrayList<>();
            final List<StructurePosePayload.PoseUpdate> poses = new ArrayList<>();
            final List<StructureBlockEntityUpdate> blockEntityUpdates = new ArrayList<>();
            diff(physics, state, added, removed, poses, blockEntityUpdates);

            if (StructureSyncDebug.enabled() && (!added.isEmpty() || !removed.isEmpty()
                    || !poses.isEmpty() || !blockEntityUpdates.isEmpty())) {
                StructureSyncDebug.log("tick {} added={} removed={} poses={} blockEntities={}",
                        level.dimension().identifier(), added.size(), removed.size(), poses.size(),
                        blockEntityUpdates.size());
            }

            if (players.isEmpty()) {
                continue;
            }

            for (ServerPlayer player : players) {
                final UUID id = player.getUUID();
                final ResourceKey<Level> previousLevel = PLAYER_LEVELS.put(id, level.dimension());
                final boolean dimensionChanged = previousLevel != null && !previousLevel.equals(level.dimension());
                final Long syncId = PLAYER_SYNC_IDS.get(id);

                if (dimensionChanged || !SYNCED_PLAYERS.contains(id) || syncId == null) {
                    if (sendFullSync(player, physics)) {
                        SYNCED_PLAYERS.add(id);
                    } else {
                        SYNCED_PLAYERS.remove(id);
                    }
                    continue;
                }

                boolean delivered = true;
                if (!removed.isEmpty()) {
                    delivered = sendRemovals(player, removed, syncId);
                }
                if (delivered && !added.isEmpty()) {
                    delivered = sendSnapshotBatches(player, added,
                            level.dimension().identifier(), syncId, false);
                }
                if (delivered && !blockEntityUpdates.isEmpty()) {
                    delivered = sendBlockEntityUpdates(player, blockEntityUpdates, syncId);
                }
                if (delivered && !poses.isEmpty()) {
                    delivered = sendPoses(player, poses, syncId);
                }
                if (!delivered) {
                    // Force a full resync on the next tick instead of leaving
                    // the client with a partially applied delta.
                    SYNCED_PLAYERS.remove(id);
                    PLAYER_SYNC_IDS.remove(id);
                }
            }
        }
    }

    /**
     * Compares current structures with the last tick and updates the state.
     * A structure change is a new {@code VoxelVolume} or collider object; the
     * current API requires callers to use {@code PhysicsStructure#reindex}
     * rather than mutating a volume in place.
     */
    private static void diff(LevelPhysics physics, LevelState state,
                             List<StructureSnapshot> added,
                             List<Long> removed,
                             List<StructurePosePayload.PoseUpdate> poses,
                             List<StructureBlockEntityUpdate> blockEntityUpdates) {
        final Map<Long, PhysicsStructure> current = new HashMap<>();
        if (physics != null) {
            for (PhysicsStructure structure : physics.structures()) {
                if (structure != null && !structure.closed()) {
                    current.put(structure.body().id(), structure);
                }
            }
        }

        for (Long bodyId : List.copyOf(state.tracked.keySet())) {
            if (!current.containsKey(bodyId)) {
                state.tracked.remove(bodyId);
                state.blockEntities.remove(bodyId);
                if (state.sent.remove(bodyId)) {
                    removed.add(bodyId);
                }
            }
        }

        for (PhysicsStructure structure : current.values()) {
            final long bodyId = structure.body().id();
            final TrackedStructure now = new TrackedStructure(
                    structure.pose(), structure.volume(), structure.collider());
            final TrackedStructure previous = state.tracked.put(bodyId, now);

            final boolean geometryChanged = previous == null
                    || previous.volume() != now.volume()
                    || previous.collider() != now.collider();
            boolean publishable = true;
            if (geometryChanged) {
                if (capture(structure, added)) {
                    state.sent.add(bodyId);
                } else {
                    publishable = false;
                    state.blockEntities.remove(bodyId);
                    if (state.sent.remove(bodyId)) {
                        // The client has an older snapshot that we can no longer
                        // represent; drop it instead of leaving stale blocks.
                        removed.add(bodyId);
                    }
                }
            } else if (state.sent.contains(bodyId) && poseChanged(previous.pose(), now.pose())) {
                poses.add(new StructurePosePayload.PoseUpdate(bodyId, now.pose()));
            }

            if (publishable && state.sent.contains(bodyId)) {
                queueBlockEntityUpdate(physics, structure, state, geometryChanged, blockEntityUpdates);
            }
        }
    }

    private static boolean capture(PhysicsStructure structure, List<StructureSnapshot> out) {
        try {
            final StructureSnapshot snapshot = StructureSnapshotFactory.capture(structure);
            final int bytes = StructureSyncProtocol.estimateBytes(snapshot);
            if (bytes > StructureSyncLimits.MAX_STRUCTURE_BYTES) {
                throw new StructureTooLargeException("Structure body #" + snapshot.bodyId()
                        + " encodes to about " + bytes + " bytes, limit is "
                        + StructureSyncLimits.MAX_STRUCTURE_BYTES);
            }
            out.add(snapshot);
            return true;
        } catch (StructureTooLargeException e) {
            skippedBodies++;
            lastError = e.getMessage();
            Crythst.LOGGER.warn("Not syncing structure to clients: {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            skippedBodies++;
            lastError = "body #" + structure.body().id() + ": " + e;
            Crythst.LOGGER.warn("Could not capture structure body #{} for synchronisation",
                    structure.body().id(), e);
            return false;
        }
    }

    private static List<StructureSnapshot> collect(LevelPhysics physics) {
        final List<StructureSnapshot> snapshots = new ArrayList<>();
        if (physics != null) {
            for (PhysicsStructure structure : physics.structures()) {
                if (structure != null && !structure.closed()) {
                    capture(structure, snapshots);
                }
            }
        }
        return snapshots;
    }

    /**
     * Captures the authoritative block-entity map for a structure on the server
     * thread and applies the per-entry and per-structure size caps.
     *
     * <p>Non-loading: the auxiliary void level only inspects registered
     * block-entity positions. When no auxiliary level exists (for example a
     * headless test or a restored structure that has not been attached yet) the
     * persistence side table is used instead.</p>
     */
    private static Map<StructureVoxelPos, StructureBlockEntityDelta.Value> captureBlockEntityValues(
            LevelPhysics physics, PhysicsStructure structure) {
        Map<BlockPos, CompoundTag> raw;
        Map<BlockPos, Integer> openCounts = Map.of();
        try {
            final EmptyPhysicsLevel auxiliary = physics == null ? null : physics.auxiliaryLevel();
            if (auxiliary != null) {
                raw = auxiliary.captureBlockEntities(structure);
                openCounts = auxiliary.captureBlockEntityOpenCounts(structure);
            } else {
                raw = StructureBlockEntityData.get(structure);
            }
        } catch (RuntimeException e) {
            lastError = "block entities for body #" + structure.body().id() + ": " + e;
            Crythst.LOGGER.warn("Could not capture block entities for structure body #{}",
                    structure.body().id(), e);
            return Map.of();
        }
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }

        final IntBounds3 bounds = structure.volume().bounds();
        final List<Map.Entry<BlockPos, CompoundTag>> sorted = new ArrayList<>(raw.entrySet());
        sorted.sort(java.util.Comparator
                .comparingInt((Map.Entry<BlockPos, CompoundTag> entry) -> entry.getKey().getX())
                .thenComparingInt(entry -> entry.getKey().getY())
                .thenComparingInt(entry -> entry.getKey().getZ()));

        final Map<StructureVoxelPos, StructureBlockEntityDelta.Value> result = new java.util.LinkedHashMap<>();
        long totalBytes = 0L;
        for (Map.Entry<BlockPos, CompoundTag> entry : sorted) {
            final BlockPos local = entry.getKey();
            final CompoundTag data = entry.getValue();
            if (local == null || data == null) {
                continue;
            }
            final int entryBytes = 8 + data.sizeInBytes() + 15;
            if (data.sizeInBytes() > StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRY_BYTES) {
                skippedBlockEntityEntries++;
                lastError = "block entity at " + local + " of body #" + structure.body().id()
                        + " exceeds " + StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRY_BYTES + " bytes";
                continue;
            }
            if (result.size() >= StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES
                    || totalBytes + entryBytes
                    > StructureSyncLimits.MAX_BLOCK_ENTITY_BYTES_PER_STRUCTURE) {
                skippedBlockEntityEntries++;
                lastError = "block-entity budget for body #" + structure.body().id() + " exceeded";
                continue;
            }
            if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0) {
                // Defensive: side tables store bounds-relative keys.
                continue;
            }
            final int openCount = openCounts.getOrDefault(
                    new BlockPos(bounds.minX() + local.getX(),
                            bounds.minY() + local.getY(),
                            bounds.minZ() + local.getZ()),
                    -1);
            result.put(new StructureVoxelPos(local.getX(), local.getY(), local.getZ()),
                    new StructureBlockEntityDelta.Value(data, openCount));
            totalBytes += entryBytes;
        }
        return StructureBlockEntityDelta.copyOf(result);
    }

    /**
     * Captures only the transient chest opener counts. This is cheap (it visits
     * registered block-entity positions but does not serialise NBT) and lets
     * the diff run every tick without re-reading inventories.
     */
    private static Map<StructureVoxelPos, Integer> captureBlockEntityOpenCounts(
            LevelPhysics physics, PhysicsStructure structure) {
        final EmptyPhysicsLevel auxiliary = physics == null ? null : physics.auxiliaryLevel();
        if (auxiliary == null) {
            return Map.of();
        }
        try {
            final Map<BlockPos, Integer> live = auxiliary.captureBlockEntityOpenCounts(structure);
            if (live == null || live.isEmpty()) {
                return Map.of();
            }
            final IntBounds3 bounds = structure.volume().bounds();
            final Map<StructureVoxelPos, Integer> result = new HashMap<>();
            for (Map.Entry<BlockPos, Integer> entry : live.entrySet()) {
                final BlockPos absolute = entry.getKey();
                final int x = absolute.getX() - bounds.minX();
                final int y = absolute.getY() - bounds.minY();
                final int z = absolute.getZ() - bounds.minZ();
                if (x < 0 || y < 0 || z < 0) {
                    continue;
                }
                result.put(new StructureVoxelPos(x, y, z), entry.getValue());
            }
            return Map.copyOf(result);
        } catch (RuntimeException e) {
            lastError = "block-entity animation for body #" + structure.body().id() + ": " + e;
            return Map.of();
        }
    }

    private static Map<StructureVoxelPos, StructureBlockEntityDelta.Value> withOpenCounts(
            Map<StructureVoxelPos, StructureBlockEntityDelta.Value> values,
            Map<StructureVoxelPos, Integer> openCounts) {
        final Map<StructureVoxelPos, StructureBlockEntityDelta.Value> updated = new HashMap<>();
        for (Map.Entry<StructureVoxelPos, StructureBlockEntityDelta.Value> entry : values.entrySet()) {
            final Integer openCount = openCounts.get(entry.getKey());
            updated.put(entry.getKey(), openCount == null
                    ? entry.getValue()
                    : entry.getValue().withOpenCount(openCount));
        }
        return StructureBlockEntityDelta.copyOf(updated);
    }

    private static void queueBlockEntityUpdate(LevelPhysics physics, PhysicsStructure structure,
                                               LevelState state, boolean forceFull,
                                               List<StructureBlockEntityUpdate> out) {
        final long bodyId = structure.body().id();
        BodyBlockEntities previous = state.blockEntities.get(bodyId);
        if (!forceFull && previous == null) {
            forceFull = true;
        }

        final Map<StructureVoxelPos, Integer> liveOpenCounts =
                captureBlockEntityOpenCounts(physics, structure);
        final boolean sourceChanged = previous == null
                || previous.sourceRevision() != structure.blockEntityRevision();
        if (!forceFull && previous != null
                && !sourceChanged
                && previous.openCounts().equals(liveOpenCounts)) {
            return;
        }

        final Map<StructureVoxelPos, StructureBlockEntityDelta.Value> current;
        if (!forceFull && previous != null && !sourceChanged) {
            // Animation-only change: update opener counts in place and skip
            // serialising inventories/signs entirely.
            current = withOpenCounts(previous.values(), liveOpenCounts);
        } else {
            current = captureBlockEntityValues(physics, structure);
        }

        final boolean startingFull = forceFull;
        List<StructureBlockEntity> entries;
        List<StructureVoxelPos> removed;
        if (startingFull) {
            entries = StructureBlockEntityDelta.fullEntries(current);
            removed = List.of();
            if (entries.isEmpty() && previous == null) {
                return;
            }
        } else {
            final StructureBlockEntityDelta.Result delta =
                    StructureBlockEntityDelta.compute(previous.values(), current);
            if (delta.isEmpty()) {
                // Source revision can change for non-renderable state; keep the
                // new revision so we do not re-capture every tick.
                state.blockEntities.put(bodyId, new BodyBlockEntities(
                        previous.revision(), current,
                        structure.blockEntityRevision(), liveOpenCounts));
                return;
            }
            entries = delta.changed();
            removed = delta.removed();
        }

        final long revision = NEXT_BLOCK_ENTITY_REVISION.incrementAndGet();
        final long baseRevision = previous == null ? 0L : previous.revision();
        try {
            final List<StructureBlockEntityUpdate> fragments =
                    StructureSyncProtocol.fragmentBlockEntityUpdate(bodyId, baseRevision, revision,
                            startingFull, entries, removed);
            out.addAll(fragments);
            state.blockEntities.put(bodyId, new BodyBlockEntities(
                    revision, current, structure.blockEntityRevision(), liveOpenCounts));
        } catch (StructureTooLargeException e) {
            skippedBlockEntityEntries++;
            lastError = e.getMessage();
            Crythst.LOGGER.warn("Not syncing structure block entities: {}", e.getMessage());
        } catch (RuntimeException e) {
            skippedBlockEntityEntries++;
            lastError = "block entities for body #" + bodyId + ": " + e;
            Crythst.LOGGER.warn("Could not build block-entity update for body #{}", bodyId, e);
        }
    }

    private static List<StructureBlockEntityUpdate> fullBlockEntityUpdates(
            LevelState state, List<StructureSnapshot> snapshots) {
        final List<StructureBlockEntityUpdate> updates = new ArrayList<>();
        if (state == null) {
            return updates;
        }
        // Reuse the last globally published revision. Advancing it during a
        // single player's full sync would desynchronise every other player,
        // because dirty updates carry the global revision as their base.
        for (StructureSnapshot snapshot : snapshots) {
            final BodyBlockEntities published = state.blockEntities.get(snapshot.bodyId());
            if (published == null || published.values().isEmpty()) {
                // No block entities have been published yet. The next tick's
                // diff creates the first revision for every player.
                continue;
            }
            final List<StructureBlockEntity> entries =
                    StructureBlockEntityDelta.fullEntries(published.values());
            try {
                updates.addAll(StructureSyncProtocol.fragmentBlockEntityUpdate(
                        snapshot.bodyId(), 0L, published.revision(), true, entries, List.of()));
            } catch (StructureTooLargeException e) {
                skippedBlockEntityEntries++;
                lastError = e.getMessage();
                Crythst.LOGGER.warn("Not syncing structure block entities: {}", e.getMessage());
            }
        }
        return updates;
    }

    private static boolean sendFullSync(ServerPlayer player, LevelPhysics physics) {
        final boolean canSend;
        try {
            canSend = ServerPlayNetworking.canSend(player, StructureSnapshotPayload.TYPE);
        } catch (RuntimeException e) {
            lastError = "channel query: " + e;
            Crythst.LOGGER.warn("Could not query structure sync channel for {}", player.getUUID(), e);
            return false;
        }
        if (!canSend) {
            Crythst.LOGGER.debug("Client {} has not registered structure sync; skipping snapshot",
                    player.getUUID());
            return false;
        }

        final long syncId = NEXT_SYNC_ID.incrementAndGet();
        final List<StructureSnapshot> snapshots = collect(physics);
        // Use the physics source level, not the player's current level: during
        // a dimension transfer the old level may still list the player while
        // we are sending snapshots for that source world.
        final Identifier dimension = physics != null
                ? physics.level().dimension().identifier()
                : player.level().dimension().identifier();
        final LevelState state = physics == null ? null
                : LEVEL_STATES.computeIfAbsent(physics.level().dimension(), key -> new LevelState());
        final List<StructureBlockEntityUpdate> blockEntityUpdates =
                fullBlockEntityUpdates(state, snapshots);
        if (!sendSnapshotBatches(player, snapshots, dimension, syncId, true)) {
            return false;
        }
        if (!blockEntityUpdates.isEmpty() && !sendBlockEntityUpdates(player, blockEntityUpdates, syncId)) {
            return false;
        }
        PLAYER_SYNC_IDS.put(player.getUUID(), syncId);
        StructureSyncDebug.log("full sync -> {} dimension={} sync={} structures={} blockEntityFragments={}",
                player.getUUID(), dimension, syncId, snapshots.size(), blockEntityUpdates.size());
        return true;
    }

    private static boolean sendSnapshotBatches(ServerPlayer player,
                                               List<StructureSnapshot> snapshots,
                                               Identifier dimension,
                                               long syncId,
                                               boolean replaceAll) {
        final List<List<StructureSnapshot>> batches;
        try {
            batches = StructureSyncProtocol.batchSnapshots(snapshots);
        } catch (StructureTooLargeException e) {
            lastError = e.getMessage();
            Crythst.LOGGER.warn("Could not batch structure snapshots: {}", e.getMessage());
            return false;
        }

        final int batchCount = batches.size();
        for (int index = 0; index < batchCount; index++) {
            final List<StructureSnapshot> batch = batches.get(index);
            if (!send(player, new StructureSnapshotPayload(
                    syncId, dimension, batch, index, batchCount, replaceAll && index == 0))) {
                return false;
            }
            sentSnapshotBatches++;
            sentSnapshotStructures += batch.size();
        }
        return true;
    }

    private static boolean sendRemovals(ServerPlayer player, List<Long> removed, long syncId) {
        for (int start = 0; start < removed.size(); start += StructureSyncLimits.MAX_STRUCTURES_PER_BATCH) {
            final int end = Math.min(start + StructureSyncLimits.MAX_STRUCTURES_PER_BATCH, removed.size());
            final long[] ids = new long[end - start];
            for (int i = start; i < end; i++) {
                ids[i - start] = removed.get(i);
            }
            if (!send(player, new StructureRemovePayload(syncId, ids))) {
                return false;
            }
            sentRemovalBatches++;
            sentRemovals += ids.length;
        }
        return true;
    }

    private static boolean sendBlockEntityUpdates(ServerPlayer player,
                                                  List<StructureBlockEntityUpdate> updates,
                                                  long syncId) {
        // A v2 client has no receiver for this channel. Skip block entities
        // instead of disconnecting it; geometry and poses still work.
        final boolean canSend;
        try {
            canSend = ServerPlayNetworking.canSend(player, StructureBlockEntityPayload.TYPE);
        } catch (RuntimeException e) {
            lastError = "block-entity channel query: " + e;
            return false;
        }
        if (!canSend) {
            return true;
        }
        final List<List<StructureBlockEntityUpdate>> batches;
        try {
            batches = StructureSyncProtocol.batchBlockEntityUpdates(updates);
        } catch (StructureTooLargeException e) {
            lastError = e.getMessage();
            Crythst.LOGGER.warn("Could not batch structure block entities: {}", e.getMessage());
            return false;
        }
        for (List<StructureBlockEntityUpdate> batch : batches) {
            if (!send(player, new StructureBlockEntityPayload(syncId, batch))) {
                return false;
            }
            sentBlockEntityPayloads++;
            sentBlockEntityUpdates += batch.size();
            for (StructureBlockEntityUpdate update : batch) {
                sentBlockEntityEntries += update.entries().size();
            }
        }
        return true;
    }

    private static boolean sendPoses(ServerPlayer player,
                                     List<StructurePosePayload.PoseUpdate> poses,
                                     long syncId) {
        for (int start = 0; start < poses.size(); start += StructureSyncLimits.MAX_POSE_UPDATES) {
            final int end = Math.min(start + StructureSyncLimits.MAX_POSE_UPDATES, poses.size());
            final List<StructurePosePayload.PoseUpdate> batch = List.copyOf(poses.subList(start, end));
            if (!send(player, new StructurePosePayload(syncId, batch))) {
                return false;
            }
            sentPoseBatches++;
            sentPoseUpdates += batch.size();
        }
        return true;
    }

    private static boolean send(ServerPlayer player, CustomPacketPayload payload) {
        try {
            ServerPlayNetworking.send(player, payload);
            return true;
        } catch (RuntimeException e) {
            lastError = payload.type().id() + ": " + e;
            Crythst.LOGGER.warn("Could not send structure sync payload {} to {}",
                    payload.type().id(), player.getUUID(), e);
            return false;
        }
    }

    private static boolean poseChanged(Transform3d previous, Transform3d current) {
        if (previous.position().distanceSquared(current.position()) > 1.0e-6) {
            return true;
        }
        final Quaterniond a = previous.rotation();
        final Quaterniond b = current.rotation();
        final double dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w();
        return Math.abs(dot) < 1.0 - 1.0e-8;
    }

    /** Human-readable diagnostics for a debug command/overlay. */
    public static String statusText() {
        int tracked = 0;
        for (LevelState state : LEVEL_STATES.values()) {
            tracked += state.tracked.size();
        }
        final StringBuilder text = new StringBuilder(192);
        text.append("server levels=").append(LEVEL_STATES.size())
                .append(" tracked=").append(tracked)
                .append(" syncedPlayers=").append(SYNCED_PLAYERS.size())
                .append(" sessions=").append(PLAYER_SYNC_IDS.size())
                .append(" snapshotBatches=").append(sentSnapshotBatches)
                .append(" snapshotStructures=").append(sentSnapshotStructures)
                .append(" poseBatches=").append(sentPoseBatches)
                .append(" poseUpdates=").append(sentPoseUpdates)
                .append(" removalBatches=").append(sentRemovalBatches)
                .append(" removals=").append(sentRemovals)
                .append(" blockEntityPayloads=").append(sentBlockEntityPayloads)
                .append(" blockEntityFragments=").append(sentBlockEntityUpdates)
                .append(" blockEntityEntries=").append(sentBlockEntityEntries)
                .append(" blockEntitySkipped=").append(skippedBlockEntityEntries)
                .append(" skipped=").append(skippedBodies)
                .append(" lastError=").append(lastError.isEmpty() ? "none" : lastError);
        for (Map.Entry<ResourceKey<Level>, LevelState> entry : LEVEL_STATES.entrySet()) {
            text.append(" [").append(entry.getKey().identifier())
                    .append(" tracked=").append(entry.getValue().tracked.size())
                    .append(" sent=").append(entry.getValue().sent.size())
                    .append(']');
        }
        return text.toString();
    }

    /** Clears all per-server state. Called on server stop and useful in tests. */
    public static void reset() {
        LEVEL_STATES.clear();
        PLAYER_LEVELS.clear();
        PLAYER_SYNC_IDS.clear();
        SYNCED_PLAYERS.clear();
        sentSnapshotBatches = 0L;
        sentSnapshotStructures = 0L;
        sentPoseBatches = 0L;
        sentPoseUpdates = 0L;
        sentRemovalBatches = 0L;
        sentRemovals = 0L;
        sentBlockEntityPayloads = 0L;
        sentBlockEntityUpdates = 0L;
        sentBlockEntityEntries = 0L;
        skippedBlockEntityEntries = 0L;
        skippedBodies = 0L;
        lastError = "";
    }

    private record TrackedStructure(Transform3d pose, VoxelVolume volume, VoxelColliderData collider) {
    }

    private record BodyBlockEntities(
            long revision,
            Map<StructureVoxelPos, StructureBlockEntityDelta.Value> values,
            long sourceRevision,
            Map<StructureVoxelPos, Integer> openCounts) {
    }

    private static final class LevelState {
        private final Map<Long, TrackedStructure> tracked = new HashMap<>();
        private final Set<Long> sent = new HashSet<>();
        private final Map<Long, BodyBlockEntities> blockEntities = new HashMap<>();
    }
}
