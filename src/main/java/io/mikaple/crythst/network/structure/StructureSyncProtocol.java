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
}
