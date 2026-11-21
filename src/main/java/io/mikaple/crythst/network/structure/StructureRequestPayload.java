package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.Crythst;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server request for a full structure resync.
 *
 * <p>The server re-sends every structure in the player's current level. This is
 * mainly useful after a client-side resource reload or for a manual repair
 * command; automatic full syncs happen on join and on dimension change.</p>
 */
public record StructureRequestPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructureRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_request"));

    public static final StreamCodec<FriendlyByteBuf, StructureRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new StructureRequestPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
