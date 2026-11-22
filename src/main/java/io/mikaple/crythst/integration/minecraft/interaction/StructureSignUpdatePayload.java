package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.Crythst;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.SignTextSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server sign text update for a physics-structure sign.
 *
 * <p>Vanilla's {@code ServerboundSignUpdatePacket} targets a real
 * {@code BlockPos} in the player's host level. Structure signs only exist in
 * the auxiliary void level, so the update is addressed by body id and
 * structure-local voxel instead. The server re-validates ownership, reach,
 * line of sight and the live sign before writing the text.</p>
 */
public record StructureSignUpdatePayload(
        long bodyId,
        int voxelX, int voxelY, int voxelZ,
        SignTextSlot slot,
        List<String> lines
) implements CustomPacketPayload {
    private static final int LINE_COUNT = 4;
    private static final int MAX_LINE_LENGTH = 384;

    public static final CustomPacketPayload.Type<StructureSignUpdatePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_sign_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureSignUpdatePayload> STREAM_CODEC =
            StreamCodec.of(StructureSignUpdatePayload::write, StructureSignUpdatePayload::read);

    public StructureSignUpdatePayload {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        slot = slot == null ? SignTextSlot.FRONT : slot;
        final List<String> normalized = new ArrayList<>(LINE_COUNT);
        for (int i = 0; i < LINE_COUNT; i++) {
            String line = lines != null && i < lines.size() ? lines.get(i) : "";
            if (line == null) {
                line = "";
            }
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH);
            }
            normalized.add(line);
        }
        lines = List.copyOf(normalized);
    }

    private static void write(RegistryFriendlyByteBuf buf, StructureSignUpdatePayload payload) {
        buf.writeVarLong(payload.bodyId());
        buf.writeVarInt(payload.voxelX());
        buf.writeVarInt(payload.voxelY());
        buf.writeVarInt(payload.voxelZ());
        buf.writeEnum(payload.slot());
        buf.writeVarInt(payload.lines().size());
        for (String line : payload.lines()) {
            buf.writeUtf(line, MAX_LINE_LENGTH);
        }
    }

    private static StructureSignUpdatePayload read(RegistryFriendlyByteBuf buf) {
        final long bodyId = buf.readVarLong();
        final int x = buf.readVarInt();
        final int y = buf.readVarInt();
        final int z = buf.readVarInt();
        final SignTextSlot slot = buf.readEnum(SignTextSlot.class);
        final int count = buf.readVarInt();
        if (count < 0 || count > LINE_COUNT) {
            throw new DecoderException("Invalid structure sign line count " + count);
        }
        final List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf(MAX_LINE_LENGTH));
        }
        try {
            return new StructureSignUpdatePayload(bodyId, x, y, z, slot, lines);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure sign update payload", e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
