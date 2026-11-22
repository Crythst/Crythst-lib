package io.mikaple.crythst.network.structure;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers the structure-sync payload types and contains the server-side
 * batching helper.
 *
 * <p>Payload registration is idempotent so both the common and the client
 * initializer can call it; Fabric requires registration on both physical
 * sides.</p>
 */
public final class StructureSyncProtocol {
    /** Approximate per-payload header: flags, batch index/count and list count. */
    private static final int PAYLOAD_HEADER_BYTES = 32;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureSyncProtocol() {
    }

    public static void registerPayloads() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        PayloadTypeRegistry.clientboundPlay()
                .register(StructureSnapshotPayload.TYPE, StructureSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(StructureBlockEntityPayload.TYPE, StructureBlockEntityPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(StructurePosePayload.TYPE, StructurePosePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(StructureRemovePayload.TYPE, StructureRemovePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(StructureRequestPayload.TYPE, StructureRequestPayload.STREAM_CODEC);
    }

    /**
     * Splits snapshots into packets no larger than
     * {@link StructureSyncLimits#TARGET_BATCH_BYTES}, except for a single
     * structure that is larger than the target (still bounded by
     * {@link StructureSyncLimits#MAX_STRUCTURE_BYTES}).
     *
     * @return at least one batch; an empty input produces one empty batch so a
     *         full sync can clear the client cache
     * @throws StructureTooLargeException if one structure exceeds the cap
     */
    public static List<List<StructureSnapshot>> batchSnapshots(List<StructureSnapshot> snapshots) {
        final List<List<StructureSnapshot>> batches = new ArrayList<>();
        List<StructureSnapshot> current = new ArrayList<>();
        int currentBytes = PAYLOAD_HEADER_BYTES;

        for (StructureSnapshot snapshot : snapshots) {
            final int bytes = StructureSnapshotCodec.estimateBytes(snapshot);
            if (bytes > StructureSyncLimits.MAX_STRUCTURE_BYTES) {
                throw new StructureTooLargeException("Structure body #" + snapshot.bodyId()
                        + " encodes to about " + bytes + " bytes, limit is "
                        + StructureSyncLimits.MAX_STRUCTURE_BYTES);
            }

            final boolean full = current.size() >= StructureSyncLimits.MAX_STRUCTURES_PER_BATCH;
            if (!current.isEmpty() && (full || currentBytes + bytes > StructureSyncLimits.TARGET_BATCH_BYTES)) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                currentBytes = PAYLOAD_HEADER_BYTES;
            }
            current.add(snapshot);
            currentBytes += bytes;
        }

        if (!current.isEmpty() || batches.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return List.copyOf(batches);
    }

    public static int estimateBytes(StructureSnapshot snapshot) {
        return StructureSnapshotCodec.estimateBytes(snapshot);
    }

    public static int varIntSize(int value) {
        return StructureSnapshotCodec.varIntSize(value);
    }

    /**
     * Splits one body's block-entity revision into wire-sized fragments.
     *
     * <p>Removals travel in the first fragment. Each fragment stays below
     * {@link StructureSyncLimits#MAX_BLOCK_ENTITY_UPDATE_BYTES}; an individual
     * entry larger than that (or larger than the per-entry NBT cap) must be
     * filtered by the server before calling this method.</p>
     *
     * @throws StructureTooLargeException if a single entry cannot fit or the
     *                                    body needs more than
     *                                    {@link StructureSyncLimits#MAX_BLOCK_ENTITY_PARTS}
     */
    public static List<StructureBlockEntityUpdate> fragmentBlockEntityUpdate(
            long bodyId, long baseRevision, long revision, boolean replace,
            List<StructureBlockEntity> entries,
            List<StructureVoxelPos> removed) {
        final int removalBytes = (removed == null ? 0 : removed.size()) * 3 * 5;
        final int headerBytes = 10 + 10 + 10 + 1 + 5 + 5 + 5 + 5 + removalBytes;
        if (headerBytes > StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES) {
            throw new StructureTooLargeException("Block-entity removals for body #" + bodyId
                    + " exceed the update cap");
        }

        final List<List<StructureBlockEntity>> parts = new ArrayList<>();
        List<StructureBlockEntity> current = new ArrayList<>();
        int currentBytes = headerBytes;
        if (entries != null) {
            for (StructureBlockEntity entry : entries) {
                final int entryBytes = StructureBlockEntityCodec.estimateEntryBytes(entry);
                if (entryBytes + headerBytes > StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES) {
                    throw new StructureTooLargeException("Block-entity entry at " + entry.pos()
                            + " of body #" + bodyId + " encodes to about " + entryBytes
                            + " bytes, limit is "
                            + StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES);
                }
                if (!current.isEmpty() && (current.size() >= StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE
                        || currentBytes + entryBytes > StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES)) {
                    parts.add(List.copyOf(current));
                    current = new ArrayList<>();
                    currentBytes = headerBytes;
                }
                current.add(entry);
                currentBytes += entryBytes;
            }
        }
        if (!current.isEmpty() || parts.isEmpty()) {
            parts.add(List.copyOf(current));
        }
        if (parts.size() > StructureSyncLimits.MAX_BLOCK_ENTITY_PARTS) {
            throw new StructureTooLargeException("Body #" + bodyId + " needs " + parts.size()
                    + " block-entity fragments, limit is "
                    + StructureSyncLimits.MAX_BLOCK_ENTITY_PARTS);
        }

        final List<StructureBlockEntityUpdate> updates = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            updates.add(new StructureBlockEntityUpdate(
                    bodyId, baseRevision, revision, replace,
                    i, parts.size(), parts.get(i),
                    i == 0 && removed != null ? removed : List.of()));
        }
        return List.copyOf(updates);
    }

    /**
     * Groups update fragments into payloads no larger than
     * {@link StructureSyncLimits#TARGET_BLOCK_ENTITY_BATCH_BYTES}.
     *
     * @throws StructureTooLargeException if one fragment exceeds the cap
     */
    public static List<List<StructureBlockEntityUpdate>> batchBlockEntityUpdates(
            List<StructureBlockEntityUpdate> updates) {
        final List<List<StructureBlockEntityUpdate>> batches = new ArrayList<>();
        List<StructureBlockEntityUpdate> current = new ArrayList<>();
        int currentBytes = PAYLOAD_HEADER_BYTES;

        for (StructureBlockEntityUpdate update : updates) {
            final int bytes = StructureBlockEntityCodec.estimateUpdateBytes(update);
            if (bytes > StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES) {
                throw new StructureTooLargeException("Block-entity update for body #"
                        + update.bodyId() + " encodes to about " + bytes + " bytes, limit is "
                        + StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES);
            }
            if (!current.isEmpty() && (current.size() >= StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATES_PER_PAYLOAD
                    || currentBytes + bytes > StructureSyncLimits.TARGET_BLOCK_ENTITY_BATCH_BYTES)) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                currentBytes = PAYLOAD_HEADER_BYTES;
            }
            current.add(update);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return List.copyOf(batches);
    }

    public static int estimateBlockEntityUpdateBytes(StructureBlockEntityUpdate update) {
        return StructureBlockEntityCodec.estimateUpdateBytes(update);
    }

    public static int estimateBlockEntityEntryBytes(StructureBlockEntity entry) {
        return StructureBlockEntityCodec.estimateEntryBytes(entry);
    }
}
