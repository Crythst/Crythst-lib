package io.mikaple.crythst.network.structure;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One fragment of a block-entity update for a single body.
 *
 * <p>Large bodies are split into {@code partCount} fragments. {@code revision}
 * is monotonic per body; the client applies an update only when
 * {@code baseRevision} matches the revision it already has, so a lost or stale
 * update cannot silently mix old and new NBT. A full replacement sets
 * {@code replace = true} on every fragment; part 0 clears the body and starts
 * accumulation, and the last part commits the new map.</p>
 *
 * @param bodyId       physics body id
 * @param baseRevision revision the client must already hold for a dirty update
 * @param revision     new revision after applying this update
 * @param replace      whether this is the first fragment of a full replacement
 * @param partIndex    0-based fragment index
 * @param partCount    number of fragments for this revision
 * @param entries      added/changed block entities
 * @param removed      positions whose block entity disappeared
 */
public record StructureBlockEntityUpdate(
        long bodyId,
        long baseRevision,
        long revision,
        boolean replace,
        int partIndex,
        int partCount,
        List<StructureBlockEntity> entries,
        List<StructureVoxelPos> removed
) {
    public StructureBlockEntityUpdate {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        if (baseRevision < 0L || revision < 0L) {
            throw new IllegalArgumentException("revisions must be non-negative");
        }
        if (partCount < 1 || partCount > StructureSyncLimits.MAX_BLOCK_ENTITY_PARTS) {
            throw new IllegalArgumentException("Invalid block-entity part count " + partCount);
        }
        if (partIndex < 0 || partIndex >= partCount) {
            throw new IllegalArgumentException("Part index " + partIndex + " outside 0.." + (partCount - 1));
        }
        entries = List.copyOf(entries);
        removed = List.copyOf(removed);
        if (entries.size() > StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE) {
            throw new IllegalArgumentException("Too many block-entity entries: " + entries.size());
        }
        if (removed.size() > StructureSyncLimits.MAX_BLOCK_ENTITY_REMOVALS_PER_UPDATE) {
            throw new IllegalArgumentException("Too many block-entity removals: " + removed.size());
        }
        final Set<StructureVoxelPos> seen = new HashSet<>(entries.size() + removed.size());
        for (StructureBlockEntity entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!seen.add(entry.pos())) {
                throw new IllegalArgumentException("Duplicate block-entity position " + entry.pos());
            }
        }
        for (StructureVoxelPos pos : removed) {
            Objects.requireNonNull(pos, "removed position");
            if (!seen.add(pos)) {
                throw new IllegalArgumentException("Position both changed and removed: " + pos);
            }
        }
    }

    /** Whether this fragment starts a full body replacement. */
    public boolean firstReplacementPart() {
        return replace && partIndex == 0;
    }

    public boolean lastPart() {
        return partIndex == partCount - 1;
    }
}
