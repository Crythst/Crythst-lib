package io.mikaple.crythst.client.network;

import io.mikaple.crythst.network.structure.StructureVoxelPos;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable block-entity state for one synchronised structure body.
 *
 * <p>Entries are keyed by structure-local voxel. {@link #revision()} is the
 * server revision the map represents; dirty updates are only applied when
 * their base revision matches, which prevents mixed old/new NBT after a lost
 * packet.</p>
 */
public final class ClientBlockEntitySnapshot {
    private final long bodyId;
    private final long revision;
    private final long dataRevision;
    private final Map<Long, ClientBlockEntity> byPacked;

    ClientBlockEntitySnapshot(long bodyId, long revision, long dataRevision,
                              Map<Long, ClientBlockEntity> entries) {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        this.bodyId = bodyId;
        this.revision = revision;
        this.dataRevision = dataRevision;
        this.byPacked = Map.copyOf(entries);
    }

    static ClientBlockEntitySnapshot empty(long bodyId, long revision) {
        return new ClientBlockEntitySnapshot(bodyId, revision, revision, Map.of());
    }

    public long bodyId() {
        return bodyId;
    }

    public long revision() {
        return revision;
    }

    /**
     * Changes only when NBT or the set of entries changed. Animation-only
     * updates keep it stable so the renderer can reuse synthetic block entities
     * (and therefore preserve an in-flight chest lid animation).
     */
    public long dataRevision() {
        return dataRevision;
    }

    public int size() {
        return byPacked.size();
    }

    public boolean isEmpty() {
        return byPacked.isEmpty();
    }

    /** All block entities in this snapshot; the collection is immutable. */
    public Collection<ClientBlockEntity> entries() {
        return byPacked.values();
    }

    /** Block entity at a structure-local voxel, or {@code null}. */
    public ClientBlockEntity get(int x, int y, int z) {
        return byPacked.get(pack(x, y, z));
    }

    public ClientBlockEntity get(StructureVoxelPos pos) {
        return pos == null ? null : byPacked.get(pos.packed());
    }

    /**
     * Returns a mutable copy of the current entries keyed by packed position.
     * Used by the cache when applying a dirty update.
     */
    Map<Long, ClientBlockEntity> mutableEntries() {
        return new HashMap<>(byPacked);
    }

    private static long pack(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0) {
            return -1L;
        }
        return new StructureVoxelPos(x, y, z).packed();
    }
}
