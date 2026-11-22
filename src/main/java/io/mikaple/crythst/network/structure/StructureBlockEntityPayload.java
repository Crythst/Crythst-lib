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
 * Server-to-client block-entity NBT and animation updates for known bodies.
 *
 * <p>The packet starts with an explicit protocol version so a mismatched client
 * rejects the payload with a {@link DecoderException} instead of misreading
 * fields. Geometry is never resent here; the client only accepts updates for
 * bodies that exist in {@code ClientStructureCache}.</p>
 */
public record StructureBlockEntityPayload(
        long syncId,
        List<StructureBlockEntityUpdate> updates
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructureBlockEntityPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_block_entities"));

    public static final StreamCodec<FriendlyByteBuf, StructureBlockEntityPayload> STREAM_CODEC =
            StreamCodec.of(StructureBlockEntityPayload::write, StructureBlockEntityPayload::read);

    public StructureBlockEntityPayload {
        if (syncId < 0L) {
            throw new IllegalArgumentException("syncId must be non-negative: " + syncId);
        }
        updates = List.copyOf(updates);
        if (updates.size() > StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATES_PER_PAYLOAD) {
            throw new IllegalArgumentException("Too many block-entity updates: " + updates.size());
        }
    }

    private static void write(FriendlyByteBuf buf, StructureBlockEntityPayload payload) {
        buf.writeVarInt(StructureSyncLimits.PROTOCOL_VERSION);
        buf.writeVarLong(payload.syncId());
        buf.writeVarInt(payload.updates().size());
        for (StructureBlockEntityUpdate update : payload.updates()) {
            StructureBlockEntityCodec.writeUpdate(buf, update);
        }
    }

    private static StructureBlockEntityPayload read(FriendlyByteBuf buf) {
        final int version = buf.readVarInt();
        if (version != StructureSyncLimits.PROTOCOL_VERSION) {
            throw new DecoderException("Unsupported structure block-entity protocol version " + version
                    + ", expected " + StructureSyncLimits.PROTOCOL_VERSION);
        }
        final long syncId = buf.readVarLong();
        final int count = buf.readVarInt();
        if (count < 0 || count > StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATES_PER_PAYLOAD) {
            throw new DecoderException("Invalid block-entity update count " + count);
        }
        final List<StructureBlockEntityUpdate> updates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            updates.add(StructureBlockEntityCodec.readUpdate(buf));
        }
        try {
            return new StructureBlockEntityPayload(syncId, updates);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure block-entity payload", e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
