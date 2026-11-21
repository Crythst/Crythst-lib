package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.Crythst;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Tiny client-to-server request for structure interaction.
 *
 * <p>The payload deliberately carries no world position. The server re-runs the
 * raycast from the player's authoritative position and look vector, so a
 * malicious or desynchronised client cannot edit arbitrary voxels.</p>
 */
public record StructureActionPayload(StructureAction action) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructureActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeByte(payload.action().ordinal()),
                    buffer -> new StructureActionPayload(StructureAction.fromId(buffer.readUnsignedByte()))
            );

    public StructureActionPayload {
        if (action == null) {
            action = StructureAction.MINE;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
