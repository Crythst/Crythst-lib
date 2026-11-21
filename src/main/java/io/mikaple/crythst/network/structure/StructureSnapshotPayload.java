package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.Crythst;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client full or partial structure snapshot.
 *
 * <p>{@code syncId} is a per-player session token. The first packet of a full
 * sync has {@code batchIndex == 0} and {@code replaceAll == true}; receiving it
 * makes the client drop its cache, adopt this session and apply the batch.
 * Delta additions use {@code replaceAll == false}; pose/remove packets from
 * another session are ignored. This prevents body ids that collide across
 * dimensions (or stale packets from a previous level) from corrupting the
 * current view.</p>
 */
public record StructureSnapshotPayload(
        long syncId,
        Identifier dimension,
        List<StructureSnapshot> structures,
        int batchIndex,
        int batchCount,
        boolean replaceAll
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructureSnapshotPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, StructureSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(StructureSnapshotPayload::write, StructureSnapshotPayload::read);

    public StructureSnapshotPayload {
        if (syncId < 0L) {
            throw new IllegalArgumentException("syncId must be non-negative: " + syncId);
        }
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        structures = List.copyOf(structures);
        if (batchCount < 1 || batchCount > StructureSyncLimits.MAX_BATCHES) {
            throw new IllegalArgumentException("Invalid batch count " + batchCount);
        }
        if (batchIndex < 0 || batchIndex >= batchCount) {
            throw new IllegalArgumentException("Batch index " + batchIndex
                    + " outside 0.." + (batchCount - 1));
        }
        if (structures.size() > StructureSyncLimits.MAX_STRUCTURES_PER_BATCH) {
            throw new IllegalArgumentException("Too many structures in one batch: " + structures.size());
        }
    }

    public boolean firstBatch() {
        return batchIndex == 0;
    }

    private static void write(FriendlyByteBuf buf, StructureSnapshotPayload payload) {
        buf.writeVarLong(payload.syncId());
        buf.writeIdentifier(payload.dimension());
        buf.writeBoolean(payload.replaceAll());
        buf.writeVarInt(payload.batchIndex());
        buf.writeVarInt(payload.batchCount());
        buf.writeVarInt(payload.structures().size());
        for (StructureSnapshot snapshot : payload.structures()) {
            StructureSnapshotCodec.write(buf, snapshot);
        }
    }

    private static StructureSnapshotPayload read(FriendlyByteBuf buf) {
        final long syncId = buf.readVarLong();
        if (syncId < 0L) {
            throw new DecoderException("Invalid sync id " + syncId);
        }
        final Identifier dimension = buf.readIdentifier();
        final boolean replaceAll = buf.readBoolean();
        final int batchIndex = buf.readVarInt();
        final int batchCount = buf.readVarInt();
        if (batchCount < 1 || batchCount > StructureSyncLimits.MAX_BATCHES) {
            throw new DecoderException("Invalid batch count " + batchCount);
        }
        if (batchIndex < 0 || batchIndex >= batchCount) {
            throw new DecoderException("Batch index " + batchIndex + " outside 0.." + (batchCount - 1));
        }
        final int count = buf.readVarInt();
        if (count < 0 || count > StructureSyncLimits.MAX_STRUCTURES_PER_BATCH) {
            throw new DecoderException("Invalid structure count " + count);
        }
        final List<StructureSnapshot> structures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            structures.add(StructureSnapshotCodec.read(buf));
        }
        try {
            return new StructureSnapshotPayload(
                    syncId, dimension, structures, batchIndex, batchCount, replaceAll);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure snapshot payload", e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
