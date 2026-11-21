package io.mikaple.crythst.client.network;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.network.structure.StructureSyncDebug;
import io.mikaple.crythst.physics.tick.PoseTimeline;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.HashMap;
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
 * nothing changed. The cache never touches a server {@code Level}.</p>
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
    private static volatile long revision;
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
        for (StructureSnapshot wire : payload.structures()) {
            next.put(wire.bodyId(), ClientStructureSnapshot.fromWire(wire));
            timeline(wire.bodyId()).reset(wire.pose(), receivedNanos);
        }
        // Drop timelines for bodies that are no longer present. On a batched
        // full sync this only removes bodies from the previous session; later
        // batches create their own timelines when they arrive.
        POSE_TIMELINES.keySet().retainAll(next.keySet());
        snapshots = Map.copyOf(next);
        revision++;
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
        boolean changed = false;
        for (long bodyId : ids) {
            if (next.remove(bodyId) != null) {
                changed = true;
            } else {
                unknownRemovals++;
            }
            POSE_TIMELINES.remove(bodyId);
        }
        if (changed) {
            snapshots = Map.copyOf(next);
            revision++;
        }
        appliedRemovals++;
        StructureSyncDebug.log("client remove sync={} ids={} changed={} total={}",
                payload.syncId(), ids.length, changed, snapshots.size());
        return true;
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
                + " unknownRemovalIds=" + unknownRemovals;
    }

    public static synchronized void clear() {
        snapshots = Map.of();
        POSE_TIMELINES.clear();
        activeSyncId = 0L;
        activeDimension = null;
        lastBatchIndex = -1;
        lastBatchCount = 0;
        lastSnapshotCount = 0;
        revision++;
    }
}
