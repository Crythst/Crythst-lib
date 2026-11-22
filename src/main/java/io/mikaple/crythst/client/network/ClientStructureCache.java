package io.mikaple.crythst.client.network;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructureBlockEntity;
import io.mikaple.crythst.network.structure.StructureBlockEntityPayload;
import io.mikaple.crythst.network.structure.StructureBlockEntityUpdate;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.network.structure.StructureSyncDebug;
import io.mikaple.crythst.network.structure.StructureVoxelPos;
import io.mikaple.crythst.physics.tick.PoseTimeline;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of physics structures received from the server.
 *
 * <p>Mutating methods must run on the client thread. Fabric invokes the
 * receiver on the render thread and {@code StructureSyncClient} also wraps the
 * call in {@code Minecraft#execute}, so this is guaranteed in practice.</p>
 *
 * <p>Each full snapshot establishes a per-player {@code syncId}. Pose and
 * removal packets from another session are ignored, which protects the cache
 * from body ids that collide across dimensions or from stale packets that were
 * queued before a dimension change.</p>
 *
 * <p>Renderers should call {@link #snapshots()} or {@link #byId()} once per
 * frame/tick and use {@link #revision()} to avoid rebuilding meshes when
 * nothing changed. Block-entity NBT/animation is kept in a separate immutable
 * map keyed by body id and structure-local voxel; see
 * {@link #blockEntities(long)} and {@link #blockEntityAt(long, int, int, int)}.
 * The cache never touches a server {@code Level}.</p>
 */
public final class ClientStructureCache {
    private static volatile Map<Long, ClientStructureSnapshot> snapshots = Map.of();
    /**
     * Timestamped pose timelines for network structures. Pose packets arrive
     * at roughly 20 Hz (and only when the server pose changed), so the renderer
     * must not read {@link ClientStructureSnapshot#pose()} directly. Entries
     * are mutated in place by {@link #applyPose}; {@link PoseTimeline} is
     * internally immutable/volatile and safe to sample from the render thread.
     */
    private static final Map<Long, PoseTimeline> POSE_TIMELINES = new ConcurrentHashMap<>();
    private static volatile Map<Long, ClientBlockEntitySnapshot> blockEntities = Map.of();
    private static final Map<Long, PendingBlockEntities> PENDING_BLOCK_ENTITIES = new HashMap<>();
    private static volatile long revision;
    private static volatile long blockEntityRevision;
    private static volatile long activeSyncId;
    private static volatile Identifier activeDimension;
    private static volatile int lastBatchIndex = -1;
    private static volatile int lastBatchCount;
    private static volatile int lastSnapshotCount;
    private static volatile long appliedBatches;
    private static volatile long appliedPoses;
    private static volatile long appliedRemovals;
    private static volatile long stalePackets;
    private static volatile long unknownPoseUpdates;
    private static volatile long unknownRemovals;
    private static volatile long appliedBlockEntityPayloads;
    private static volatile long appliedBlockEntityEntries;
    private static volatile long blockEntityDesyncs;
    private static volatile long unknownBlockEntityBodies;
    private static volatile boolean blockEntityResyncRequested;

    private ClientStructureCache() {
    }

    /**
     * Applies one server snapshot batch.
     *
     * @return {@code false} when the packet belongs to a stale session
     */
    public static synchronized boolean apply(StructureSnapshotPayload payload) {
        return apply(payload, System.nanoTime());
    }

    /**
     * Timed overload used by tests and by callers that already have a receive
     * timestamp. Full snapshots are treated as snap points: a resync must not
     * interpolate from a pose that may be seconds old.
     */
    static synchronized boolean apply(StructureSnapshotPayload payload, long receivedNanos) {
        final boolean reset = payload.replaceAll() && payload.firstBatch();
        if (!reset && payload.syncId() != activeSyncId) {
            stalePackets++;
            StructureSyncDebug.log("client ignored snapshot sync={} active={} batch={}/{}",
                    payload.syncId(), activeSyncId, payload.batchIndex(), payload.batchCount());
            return false;
        }
        if (!reset && activeDimension != null && !activeDimension.equals(payload.dimension())) {
            stalePackets++;
            StructureSyncDebug.log("client ignored snapshot dimension={} active={} sync={}",
                    payload.dimension(), activeDimension, payload.syncId());
            return false;
        }
        if (reset) {
            activeSyncId = payload.syncId();
            activeDimension = payload.dimension();
        }

        final Map<Long, ClientStructureSnapshot> next =
                reset ? new HashMap<>() : new HashMap<>(snapshots);
        final Map<Long, ClientBlockEntitySnapshot> nextBlockEntities =
                reset ? new HashMap<>() : new HashMap<>(blockEntities);
        if (reset) {
            PENDING_BLOCK_ENTITIES.clear();
        }
        boolean blockEntitiesChanged = reset;
        for (StructureSnapshot wire : payload.structures()) {
            next.put(wire.bodyId(), ClientStructureSnapshot.fromWire(wire));
            timeline(wire.bodyId()).reset(wire.pose(), receivedNanos);
            // Geometry (and therefore the local voxel space) may have changed.
            // Drop the previous block-entity map; the server follows every
            // snapshot with a full block-entity replacement for this body.
            if (nextBlockEntities.remove(wire.bodyId()) != null) {
                blockEntitiesChanged = true;
            }
            if (PENDING_BLOCK_ENTITIES.remove(wire.bodyId()) != null) {
                blockEntitiesChanged = true;
            }
        }
        // Drop timelines for bodies that are no longer present. On a batched
        // full sync this only removes bodies from the previous session; later
        // batches create their own timelines when they arrive.
        POSE_TIMELINES.keySet().retainAll(next.keySet());
        snapshots = Map.copyOf(next);
        blockEntities = Map.copyOf(nextBlockEntities);
        revision++;
        if (blockEntitiesChanged) {
            blockEntityRevision++;
        }
        appliedBatches++;
        lastBatchIndex = payload.batchIndex();
        lastBatchCount = payload.batchCount();
        lastSnapshotCount = payload.structures().size();
        StructureSyncDebug.log("client snapshot sync={} batch={}/{} structures={} total={} replaceAll={}",
                payload.syncId(), payload.batchIndex(), payload.batchCount(),
                payload.structures().size(), snapshots.size(), reset);
        return true;
    }

    /**
     * Applies a batched pose update; unknown body ids are ignored.
     *
     * @return {@code false} when the packet belongs to a stale session
     */
    public static synchronized boolean applyPose(StructurePosePayload payload) {
        return applyPose(payload, System.nanoTime());
    }

    /**
     * Timed overload used by tests and by callers that already have a receive
     * timestamp. Poses are pushed into the per-body render timeline; the
     * snapshot's raw pose is only kept as a fallback for callers that have no
     * timeline yet.
     */
    static synchronized boolean applyPose(StructurePosePayload payload, long receivedNanos) {
        if (payload.syncId() != activeSyncId) {
            stalePackets++;
            StructureSyncDebug.log("client ignored pose sync={} active={} updates={}",
                    payload.syncId(), activeSyncId, payload.updates().size());
            return false;
        }
        if (payload.updates().isEmpty()) {
            return true;
        }
        final Map<Long, ClientStructureSnapshot> next = new HashMap<>(snapshots);
        boolean changed = false;
        for (StructurePosePayload.PoseUpdate update : payload.updates()) {
            final ClientStructureSnapshot existing = next.get(update.bodyId());
            if (existing != null) {
                next.put(update.bodyId(), existing.withPose(update.pose()));
                timeline(update.bodyId()).push(update.pose(), receivedNanos);
                changed = true;
            } else {
                unknownPoseUpdates++;
            }
        }
        if (changed) {
            snapshots = Map.copyOf(next);
            revision++;
        }
        appliedPoses++;
        StructureSyncDebug.log("client pose sync={} updates={} knownChanged={} total={}",
                payload.syncId(), payload.updates().size(), changed, snapshots.size());
        return true;
    }

    /**
     * Removes structures by body id.
     *
     * @return {@code false} when the packet belongs to a stale session
     */
    public static synchronized boolean applyRemove(StructureRemovePayload payload) {
        if (payload.syncId() != activeSyncId) {
            stalePackets++;
            StructureSyncDebug.log("client ignored remove sync={} active={} ids={}",
                    payload.syncId(), activeSyncId, payload.bodyIds().length);
            return false;
        }
        final long[] ids = payload.bodyIds();
        if (ids.length == 0) {
            return true;
        }
        final Map<Long, ClientStructureSnapshot> next = new HashMap<>(snapshots);
        final Map<Long, ClientBlockEntitySnapshot> nextBlockEntities = new HashMap<>(blockEntities);
        boolean changed = false;
        boolean blockEntitiesChanged = false;
        for (long bodyId : ids) {
            if (next.remove(bodyId) != null) {
                changed = true;
            } else {
                unknownRemovals++;
            }
            if (nextBlockEntities.remove(bodyId) != null) {
                blockEntitiesChanged = true;
            }
            PENDING_BLOCK_ENTITIES.remove(bodyId);
            POSE_TIMELINES.remove(bodyId);
        }
        if (changed) {
            snapshots = Map.copyOf(next);
            revision++;
        }
        if (blockEntitiesChanged) {
            blockEntities = Map.copyOf(nextBlockEntities);
            blockEntityRevision++;
        }
        appliedRemovals++;
        StructureSyncDebug.log("client remove sync={} ids={} changed={} total={}",
                payload.syncId(), ids.length, changed, snapshots.size());
        return true;
    }

    /**
     * Applies a block-entity payload. Geometry must already be present; updates
     * for unknown bodies are ignored.
     *
     * @return {@code false} when the packet belongs to a stale session
     */
    public static synchronized boolean applyBlockEntities(StructureBlockEntityPayload payload) {
        if (payload.syncId() != activeSyncId) {
            stalePackets++;
            StructureSyncDebug.log("client ignored block entities sync={} active={} updates={}",
                    payload.syncId(), activeSyncId, payload.updates().size());
            return false;
        }
        if (payload.updates().isEmpty()) {
            return true;
        }

        final Map<Long, ClientBlockEntitySnapshot> next = new HashMap<>(blockEntities);
        boolean changed = false;
        int appliedEntries = 0;
        for (StructureBlockEntityUpdate update : payload.updates()) {
            final long bodyId = update.bodyId();
            final ClientStructureSnapshot geometry = snapshots.get(bodyId);
            if (geometry == null) {
                unknownBlockEntityBodies++;
                continue;
            }

            // Full replacements and fragmented dirty sets both accumulate in a
            // pending buffer. This keeps a multi-part update atomic: the client
            // never exposes half a container map, and dirty fragments share one
            // base revision instead of advancing it per part.
            final boolean pendingUpdate = update.replace() || update.partCount() > 1;
            if (pendingUpdate) {
                PendingBlockEntities pending = PENDING_BLOCK_ENTITIES.get(bodyId);
                if (update.partIndex() == 0) {
                    pending = new PendingBlockEntities(update.revision(), update.baseRevision(),
                            update.replace(), update.partCount());
                    PENDING_BLOCK_ENTITIES.put(bodyId, pending);
                } else if (pending == null
                        || pending.revision != update.revision()
                        || pending.baseRevision != update.baseRevision()
                        || pending.replace != update.replace()
                        || pending.partCount != update.partCount()) {
                    PENDING_BLOCK_ENTITIES.remove(bodyId);
                    blockEntityDesyncs++;
                    blockEntityResyncRequested = true;
                    continue;
                }

                for (StructureBlockEntity entry : update.entries()) {
                    pending.entries.put(entry.pos().packed(), entry);
                    appliedEntries++;
                }
                pending.removed.addAll(update.removed());
                if (update.lastPart()) {
                    if (commitPending(next, bodyId, pending)) {
                        changed = true;
                    }
                    PENDING_BLOCK_ENTITIES.remove(bodyId);
                }
                continue;
            }

            final ClientBlockEntitySnapshot current = next.get(bodyId);
            if (current == null || update.baseRevision() != current.revision()) {
                dropForDesync(next, bodyId);
                changed = true;
                continue;
            }

            final Map<Long, ClientBlockEntity> entries = current.mutableEntries();
            boolean dataChanged = !update.removed().isEmpty();
            for (StructureBlockEntity entry : update.entries()) {
                if (applyEntry(entries, entry)) {
                    appliedEntries++;
                    dataChanged |= entry.data() != null;
                }
            }
            for (StructureVoxelPos pos : update.removed()) {
                if (entries.remove(pos.packed()) != null) {
                    appliedEntries++;
                }
            }
            final long dataRevision = dataChanged ? update.revision() : current.dataRevision();
            next.put(bodyId, new ClientBlockEntitySnapshot(
                    bodyId, update.revision(), dataRevision, entries));
            changed = true;
        }

        if (changed) {
            blockEntities = Map.copyOf(next);
            blockEntityRevision++;
        }
        appliedBlockEntityPayloads++;
        appliedBlockEntityEntries += appliedEntries;
        StructureSyncDebug.log("client block entities sync={} updates={} changed={} bodies={} entries={}",
                payload.syncId(), payload.updates().size(), changed, blockEntities.size(), appliedEntries);
        return true;
    }

    /**
     * Applies an accumulated multi-fragment update atomically.
     *
     * @return always {@code true}; the caller marks the map as changed
     */
    private static boolean commitPending(Map<Long, ClientBlockEntitySnapshot> next,
                                         long bodyId, PendingBlockEntities pending) {
        if (pending.replace) {
            final Map<Long, ClientBlockEntity> entries = new HashMap<>();
            for (StructureBlockEntity entry : pending.entries.values()) {
                entries.put(entry.pos().packed(),
                        new ClientBlockEntity(entry.pos(), entry.data(), entry.openCount()));
            }
            next.put(bodyId, new ClientBlockEntitySnapshot(
                    bodyId, pending.revision, pending.revision, entries));
            return true;
        }

        final ClientBlockEntitySnapshot current = next.get(bodyId);
        if (current == null || pending.baseRevision != current.revision()) {
            dropForDesync(next, bodyId);
            return true;
        }
        final Map<Long, ClientBlockEntity> entries = current.mutableEntries();
        boolean dataChanged = !pending.removed.isEmpty();
        for (StructureBlockEntity entry : pending.entries.values()) {
            if (applyEntry(entries, entry)) {
                dataChanged |= entry.data() != null;
            }
        }
        for (StructureVoxelPos pos : pending.removed) {
            entries.remove(pos.packed());
        }
        final long dataRevision = dataChanged ? pending.revision : current.dataRevision();
        next.put(bodyId, new ClientBlockEntitySnapshot(
                bodyId, pending.revision, dataRevision, entries));
        return true;
    }

    private static boolean applyEntry(Map<Long, ClientBlockEntity> entries,
                                      StructureBlockEntity entry) {
        final long key = entry.pos().packed();
        final ClientBlockEntity previous = entries.get(key);
        final CompoundTag nbt = entry.data();
        if (nbt != null) {
            entries.put(key, new ClientBlockEntity(entry.pos(), nbt, entry.openCount()));
            return true;
        }
        if (previous != null) {
            // Animation-only update: keep the existing NBT/inventory.
            entries.put(key, new ClientBlockEntity(
                    entry.pos(), previous.data(), entry.openCount()));
            return true;
        }
        return false;
    }

    private static void dropForDesync(Map<Long, ClientBlockEntitySnapshot> next, long bodyId) {
        blockEntityDesyncs++;
        blockEntityResyncRequested = true;
        next.remove(bodyId);
        PENDING_BLOCK_ENTITIES.remove(bodyId);
    }

    /** Immutable block-entity state for one body, or {@code null}. */
    public static ClientBlockEntitySnapshot blockEntities(long bodyId) {
        return blockEntities.get(bodyId);
    }

    /** Block entity at a structure-local voxel, or {@code null}. */
    public static ClientBlockEntity blockEntityAt(long bodyId, int x, int y, int z) {
        final ClientBlockEntitySnapshot snapshot = blockEntities.get(bodyId);
        return snapshot == null ? null : snapshot.get(x, y, z);
    }

    public static int blockEntityBodyCount() {
        return blockEntities.size();
    }

    /**
     * Changes whenever the block-entity map is replaced. Geometry changes also
     * bump it because they invalidate the local voxel space.
     */
    public static long blockEntityRevision() {
        return blockEntityRevision;
    }

    /**
     * Consumes a pending request for a full resync after a block-entity base
     * revision mismatch. The networking layer rate-limits the actual request.
     */
    public static synchronized boolean consumeBlockEntityResyncRequest() {
        final boolean requested = blockEntityResyncRequested;
        blockEntityResyncRequested = false;
        return requested;
    }

    public static Collection<ClientStructureSnapshot> snapshots() {
        return snapshots.values();
    }

    public static Map<Long, ClientStructureSnapshot> byId() {
        return snapshots;
    }

    public static ClientStructureSnapshot get(long bodyId) {
        return snapshots.get(bodyId);
    }

    /**
     * Returns the interpolated render pose for a network structure at an
     * explicit monotonic frame time.
     *
     * <p>The newest pose is delayed by one smoothed receive interval. The
     * result falls back to the snapshot pose when no timeline exists yet (for
     * example a body that arrived before interpolation was enabled).</p>
     */
    public static Transform3d interpolatedPose(long bodyId, long nowNanos) {
        final PoseTimeline timeline = POSE_TIMELINES.get(bodyId);
        if (timeline != null) {
            return timeline.sample(nowNanos);
        }
        final ClientStructureSnapshot snapshot = snapshots.get(bodyId);
        return snapshot == null ? Transform3d.IDENTITY : snapshot.pose();
    }

    /** Number of bodies currently carrying a render pose timeline. */
    public static int interpolatedBodyCount() {
        return POSE_TIMELINES.size();
    }

    /**
     * Smoothed publish interval for a body's pose timeline, in seconds.
     *
     * <p>Used by the public client pose API to derive a finite-difference
     * velocity without exposing the timeline itself. Returns the 20 TPS
     * default when no timeline exists yet.</p>
     */
    public static double interpolatedIntervalSeconds(long bodyId) {
        final PoseTimeline timeline = POSE_TIMELINES.get(bodyId);
        if (timeline == null) {
            return 0.05;
        }
        return Math.max(0.05, timeline.delayNanos() / 1.0e9);
    }

    private static PoseTimeline timeline(long bodyId) {
        return POSE_TIMELINES.computeIfAbsent(bodyId, ignored -> new PoseTimeline());
    }

    public static int size() {
        return snapshots.size();
    }

    /** Monotonic counter that changes whenever the snapshot map changes. */
    public static long revision() {
        return revision;
    }

    /** Session id established by the latest full snapshot, or 0 before one. */
    public static long activeSyncId() {
        return activeSyncId;
    }

    /** Dimension adopted by the latest full snapshot, or {@code null}. */
    public static Identifier activeDimension() {
        return activeDimension;
    }

    /** Human-readable diagnostics for a debug command/overlay. */
    public static String statusText() {
        return "client structures=" + snapshots.size()
                + " interpolated=" + POSE_TIMELINES.size()
                + " revision=" + revision
                + " syncId=" + activeSyncId
                + " dimension=" + (activeDimension == null ? "none" : activeDimension)
                + " lastBatch=" + lastBatchIndex + "/" + lastBatchCount
                + " lastBatchStructures=" + lastSnapshotCount
                + " batches=" + appliedBatches
                + " poses=" + appliedPoses
                + " removals=" + appliedRemovals
                + " stale=" + stalePackets
                + " unknownPoseIds=" + unknownPoseUpdates
                + " unknownRemovalIds=" + unknownRemovals
                + " blockEntityBodies=" + blockEntities.size()
                + " blockEntityEntries=" + blockEntityEntryCount()
                + " blockEntityRevision=" + blockEntityRevision
                + " blockEntityPayloads=" + appliedBlockEntityPayloads
                + " blockEntityEntriesApplied=" + appliedBlockEntityEntries
                + " blockEntityDesyncs=" + blockEntityDesyncs
                + " unknownBlockEntityIds=" + unknownBlockEntityBodies;
    }

    private static int blockEntityEntryCount() {
        int total = 0;
        for (ClientBlockEntitySnapshot snapshot : blockEntities.values()) {
            total += snapshot.size();
        }
        return total;
    }

    public static synchronized void clear() {
        snapshots = Map.of();
        blockEntities = Map.of();
        PENDING_BLOCK_ENTITIES.clear();
        POSE_TIMELINES.clear();
        activeSyncId = 0L;
        activeDimension = null;
        lastBatchIndex = -1;
        lastBatchCount = 0;
        lastSnapshotCount = 0;
        blockEntityResyncRequested = false;
        revision++;
        blockEntityRevision++;
    }

    /** Mutable accumulator for a fragmented full replacement. */
    private static final class PendingBlockEntities {
        private final long revision;
        private final long baseRevision;
        private final boolean replace;
        private final int partCount;
        private final Map<Long, StructureBlockEntity> entries = new HashMap<>();
        private final List<StructureVoxelPos> removed = new ArrayList<>();

        private PendingBlockEntities(long revision, long baseRevision,
                                     boolean replace, int partCount) {
            this.revision = revision;
            this.baseRevision = baseRevision;
            this.replace = replace;
            this.partCount = partCount;
        }
    }
}
