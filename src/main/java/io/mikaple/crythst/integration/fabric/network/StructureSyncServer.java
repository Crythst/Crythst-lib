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
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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

/**
 * Server-side structure broadcaster.
 *
 * <p>The authorititative structure list lives in {@link LevelPhysics}. This
 * class never mutates it; once per server tick it diffs a cheap fingerprint
 * (pose, volume identity, collider identity) and sends snapshots, removals and
 * batched pose updates to players in the same dimension.</p>
 *
 * <p>Registering this after the physics ticker makes the diff read poses from
 * the current tick. Registering it earlier still works but sends poses one
 * server tick behind.</p>
 */
public final class StructureSyncServer {
    private static final Map<ResourceKey<Level>, LevelState> LEVEL_STATES = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> PLAYER_LEVELS = new HashMap<>();
    private static final Set<UUID> SYNCED_PLAYERS = new HashSet<>();

    private static boolean registered;

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
        ServerTickEvents.END_SERVER_TICK.register(StructureSyncServer::tick);
        ServerLevelEvents.UNLOAD.register((server, level) -> LEVEL_STATES.remove(level.dimension()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
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

            if (players.isEmpty()) {
                continue;
            }

            for (ServerPlayer player : players) {
                final UUID id = player.getUUID();
                final ResourceKey<Level> previousLevel = PLAYER_LEVELS.put(id, level.dimension());
                final boolean dimensionChanged = previousLevel != null && !previousLevel.equals(level.dimension());

                if (dimensionChanged || !SYNCED_PLAYERS.contains(id)) {
                    if (sendFullSync(player, physics)) {
                        SYNCED_PLAYERS.add(id);
                    } else {
                        SYNCED_PLAYERS.remove(id);
                    }
                    continue;
                }

                boolean delivered = true;
                if (!removed.isEmpty()) {
                    delivered = sendRemovals(player, removed);
                }
                if (delivered && !added.isEmpty()) {
                    delivered = sendSnapshotBatches(player, added, false);
                }
                if (delivered && !poses.isEmpty()) {
                    delivered = sendPoses(player, poses);
                }
                if (!delivered) {
                    // Force a full resync on the next tick instead of leaving
                    // the client with a partially applied delta.
                    SYNCED_PLAYERS.remove(id);
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
            Crythst.LOGGER.warn("Not syncing structure to clients: {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
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
            Crythst.LOGGER.warn("Could not query structure sync channel for {}", player.getUUID(), e);
            return false;
        }
        if (!canSend) {
            Crythst.LOGGER.debug("Client {} has not registered structure sync; skipping snapshot",
                    player.getUUID());
            return false;
        }
        return sendSnapshotBatches(player, collect(physics), true);
    }

    private static boolean sendSnapshotBatches(ServerPlayer player,
                                               List<StructureSnapshot> snapshots,
                                               boolean replaceAll) {
        final List<List<StructureSnapshot>> batches;
        try {
            batches = StructureSyncProtocol.batchSnapshots(snapshots);
        } catch (StructureTooLargeException e) {
            Crythst.LOGGER.warn("Could not batch structure snapshots: {}", e.getMessage());
            return false;
        }

        final int batchCount = batches.size();
        for (int index = 0; index < batchCount; index++) {
            if (!send(player, new StructureSnapshotPayload(
                    batches.get(index), index, batchCount, replaceAll && index == 0))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sendRemovals(ServerPlayer player, List<Long> removed) {
        for (int start = 0; start < removed.size(); start += StructureSyncLimits.MAX_STRUCTURES_PER_BATCH) {
            final int end = Math.min(start + StructureSyncLimits.MAX_STRUCTURES_PER_BATCH, removed.size());
            final long[] ids = new long[end - start];
            for (int i = start; i < end; i++) {
                ids[i - start] = removed.get(i);
            }
            if (!send(player, new StructureRemovePayload(ids))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sendPoses(ServerPlayer player, List<StructurePosePayload.PoseUpdate> poses) {
        for (int start = 0; start < poses.size(); start += StructureSyncLimits.MAX_POSE_UPDATES) {
            final int end = Math.min(start + StructureSyncLimits.MAX_POSE_UPDATES, poses.size());
            if (!send(player, new StructurePosePayload(List.copyOf(poses.subList(start, end))))) {
                return false;
            }
        }
        return true;
    }

    private static boolean send(ServerPlayer player, CustomPacketPayload payload) {
        try {
            ServerPlayNetworking.send(player, payload);
            return true;
        } catch (RuntimeException e) {
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

    /** Clears all per-server state. Called on server stop and useful in tests. */
    public static void reset() {
        LEVEL_STATES.clear();
        PLAYER_LEVELS.clear();
        SYNCED_PLAYERS.clear();
    }

    private record TrackedStructure(Transform3d pose, VoxelVolume volume, VoxelColliderData collider) {
    }

    private static final class LevelState {
        private final Map<Long, TrackedStructure> tracked = new HashMap<>();
        private final Set<Long> sent = new HashSet<>();
    }
}
