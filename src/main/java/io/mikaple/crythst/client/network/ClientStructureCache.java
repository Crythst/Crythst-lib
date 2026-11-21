package io.mikaple.crythst.client.network;

import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache of physics structures received from the server.
 *
 * <p>Mutating methods must run on the client thread. Fabric invokes the
 * receiver on the render thread and {@code StructureSyncClient} also wraps the
 * call in {@code Minecraft#execute}, so this is guaranteed in practice.</p>
 *
 * <p>Renderers should call {@link #snapshots()} or {@link #byId()} once per
 * frame/tick and use {@link #revision()} to avoid rebuilding meshes when
 * nothing changed. The cache never touches a server {@code Level}.</p>
 */
public final class ClientStructureCache {
    private static volatile Map<Long, ClientStructureSnapshot> snapshots = Map.of();
    private static volatile long revision;

    private ClientStructureCache() {
    }

    /** Applies one server snapshot batch. */
    public static synchronized void apply(StructureSnapshotPayload payload) {
        final Map<Long, ClientStructureSnapshot> next;
        if (payload.replaceAll() && payload.firstBatch()) {
            next = new HashMap<>();
        } else {
            next = new HashMap<>(snapshots);
        }
        for (StructureSnapshot wire : payload.structures()) {
            next.put(wire.bodyId(), ClientStructureSnapshot.fromWire(wire));
        }
        snapshots = Map.copyOf(next);
        revision++;
    }

    /** Applies a batched pose update; unknown body ids are ignored. */
    public static synchronized void applyPose(StructurePosePayload payload) {
        if (payload.updates().isEmpty()) {
            return;
        }
        final Map<Long, ClientStructureSnapshot> next = new HashMap<>(snapshots);
        boolean changed = false;
        for (StructurePosePayload.PoseUpdate update : payload.updates()) {
            final ClientStructureSnapshot existing = next.get(update.bodyId());
            if (existing != null) {
                next.put(update.bodyId(), existing.withPose(update.pose()));
                changed = true;
            }
        }
        if (changed) {
            snapshots = Map.copyOf(next);
            revision++;
        }
    }

    /** Removes structures by body id. */
    public static synchronized void applyRemove(StructureRemovePayload payload) {
        if (payload.bodyIds().length == 0) {
            return;
        }
        final Map<Long, ClientStructureSnapshot> next = new HashMap<>(snapshots);
        boolean changed = false;
        for (long bodyId : payload.bodyIds()) {
            if (next.remove(bodyId) != null) {
                changed = true;
            }
        }
        if (changed) {
            snapshots = Map.copyOf(next);
            revision++;
        }
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

    public static int size() {
        return snapshots.size();
    }

    /** Monotonic counter that changes whenever the snapshot map changes. */
    public static long revision() {
        return revision;
    }

    public static synchronized void clear() {
        snapshots = Map.of();
        revision++;
    }
}
