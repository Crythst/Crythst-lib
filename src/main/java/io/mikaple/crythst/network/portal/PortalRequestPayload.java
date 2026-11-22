package io.mikaple.crythst.network.portal;

import io.mikaple.crythst.Crythst;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client request for a fresh authoritative portal snapshot. Sent on join and
 * after a client-side cache reset.
 */
public record PortalRequestPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PortalRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_request"));

    public static final StreamCodec<FriendlyByteBuf, PortalRequestPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
            }, buf -> new PortalRequestPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
