package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.Crythst;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client removal of one or more structures.
 *
 * <p>{@code syncId} must match the active session, so removals from a previous
 * dimension cannot delete current structures with a colliding body id.</p>
 */
public record StructureRemovePayload(long syncId, long[] bodyIds) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructureRemovePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_remove"));

    public static final StreamCodec<FriendlyByteBuf, StructureRemovePayload> STREAM_CODEC =
            StreamCodec.of(StructureRemovePayload::write, StructureRemovePayload::read);

    public StructureRemovePayload {
        if (syncId < 0L) {
            throw new IllegalArgumentException("syncId must be non-negative: " + syncId);
        }
        bodyIds = bodyIds == null ? new long[0] : bodyIds.clone();
        if (bodyIds.length > StructureSyncLimits.MAX_STRUCTURES_PER_BATCH) {
            throw new IllegalArgumentException("Too many removals: " + bodyIds.length);
        }
        for (long bodyId : bodyIds) {
            if (bodyId < 0L) {
                throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
            }
        }
    }

    @Override
    public long[] bodyIds() {
        return bodyIds.clone();
    }

    private static void write(FriendlyByteBuf buf, StructureRemovePayload payload) {
        buf.writeVarLong(payload.syncId());
        buf.writeVarInt(payload.bodyIds().length);
        for (long bodyId : payload.bodyIds()) {
            buf.writeVarLong(bodyId);
        }
    }

    private static StructureRemovePayload read(FriendlyByteBuf buf) {
        final long syncId = buf.readVarLong();
        if (syncId < 0L) {
            throw new DecoderException("Invalid sync id " + syncId);
        }
        final int count = buf.readVarInt();
        if (count < 0 || count > StructureSyncLimits.MAX_STRUCTURES_PER_BATCH) {
            throw new DecoderException("Invalid removal count " + count);
        }
        final long[] bodyIds = new long[count];
        for (int i = 0; i < count; i++) {
            bodyIds[i] = buf.readVarLong();
        }
        try {
            return new StructureRemovePayload(syncId, bodyIds);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid removal payload", e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
