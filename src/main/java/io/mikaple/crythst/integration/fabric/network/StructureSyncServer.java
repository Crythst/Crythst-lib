package io.mikaple.crythst.integration.fabric.network;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureRequestPayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.network.structure.StructureSyncDebug;
import io.mikaple.crythst.network.structure.StructureSyncLimits;
import io.mikaple.crythst.network.structure.StructureSyncProtocol;
import io.mikaple.crythst.network.structure.StructureTooLargeException;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
 * batched pose updates to players in the same dimension.</p>
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
    private static final AtomicBoolean TICK_REGISTERED = new AtomicBoolean();

    private static boolean registered;
    private static long sentSnapshotBatches;
    private static long sentSnapshotStructures;
    private static long sentPoseBatches;
    private static long sentPoseUpdates;
    private static long sentRemovalBatches;
    private static long sentRemovals;
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
            diff(physics, state, added, removed, poses);

            if (StructureSyncDebug.enabled() && (!added.isEmpty() || !removed.isEmpty() || !poses.isEmpty())) {
                StructureSyncDebug.log("tick {} added={} removed={} poses={}",
                        level.dimension().identifier(), added.size(), removed.size(), poses.size());
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
                             List<StructurePosePayload.PoseUpdate> poses) {
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

            if (previous == null
                    || previous.volume() != now.volume()
                    || previous.collider() != now.collider()) {
                if (capture(structure, added)) {
                    state.sent.add(bodyId);
                } else if (state.sent.remove(bodyId)) {
                    // The client has an older snapshot that we can no longer
                    // represent; drop it instead of leaving stale blocks.
                    removed.add(bodyId);
                }
            } else if (state.sent.contains(bodyId) && poseChanged(previous.pose(), now.pose())) {
                poses.add(new StructurePosePayload.PoseUpdate(bodyId, now.pose()));
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
        if (!sendSnapshotBatches(player, snapshots, dimension, syncId, true)) {
            return false;
        }
        PLAYER_SYNC_IDS.put(player.getUUID(), syncId);
        StructureSyncDebug.log("full sync -> {} dimension={} sync={} structures={}",
                player.getUUID(), dimension, syncId, snapshots.size());
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
        skippedBodies = 0L;
        lastError = "";
    }

    private record TrackedStructure(Transform3d pose, VoxelVolume volume, VoxelColliderData collider) {
    }

    private static final class LevelState {
        private final Map<Long, TrackedStructure> tracked = new HashMap<>();
        private final Set<Long> sent = new HashSet<>();
    }
}
