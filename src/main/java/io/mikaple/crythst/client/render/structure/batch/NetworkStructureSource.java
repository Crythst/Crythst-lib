package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts network snapshots into stable batch geometry views.
 *
 * <p>Conversion is keyed by body id and the client cache revision. Pose-only
 * updates keep the same {@link ClientStructureSnapshot#geometryRevision()}, so
 * the cached geometry wrapper (and its section meshes) is reused instead of
 * being rebuilt every frame or every server tick.</p>
 *
 * <p>Client thread only.</p>
 */
final class NetworkStructureSource {
    private final Map<Long, StructureBatchSnapshots.Snapshot> geometryByBody = new HashMap<>();
    private long cacheRevision = Long.MIN_VALUE;

    /**
     * Drops wrappers for structures removed from the client cache. Call once
     * per frame before {@link #geometry(ClientStructureSnapshot)}.
     */
    void beginFrame() {
        final long currentRevision = ClientStructureCache.revision();
        if (currentRevision == cacheRevision) {
            return;
        }
        cacheRevision = currentRevision;
        geometryByBody.keySet().retainAll(ClientStructureCache.byId().keySet());
    }

    /**
     * Returns the cached geometry view, converting only when the block volume
     * changed. The caller supplies pose/scale/COM from the current source.
     */
    StructureBatchSnapshots.Snapshot geometry(ClientStructureSnapshot source) {
        final long bodyId = source.bodyId();
        final StructureBatchSnapshots.Snapshot existing = geometryByBody.get(bodyId);
        if (existing != null && existing.contentRevision() == source.geometryRevision()) {
            return existing;
        }
        final StructureBatchSnapshots.Snapshot created = StructureBatchSnapshots.fromClient(source);
        geometryByBody.put(bodyId, created);
        return created;
    }

    void clear() {
        geometryByBody.clear();
        cacheRevision = Long.MIN_VALUE;
    }
}
