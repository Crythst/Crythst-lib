package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.Crythst;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.SignTextSlot;

/**
 * Server-to-client request to open the editor for a sign that lives inside a
 * physics structure.
 *
 * <p>The vanilla {@code ClientboundOpenSignEditorPacket} carries a real
 * {@link BlockPos} and the client resolves the sign from its own level. A
 * structure sign has no block entity in the host client level, so this payload
 * carries the identity (body id + structure-local voxel), the block state and
 * the captured sign NBT instead. The client builds a synthetic
 * {@code SignBlockEntity} for the editor and sends its text back with
 * {@link StructureSignUpdatePayload}.</p>
 *
 * @param bodyId      physics body id of the owning structure
 * @param voxelX      structure-local voxel X (same space as the interaction hit)
 * @param voxelY      structure-local voxel Y
 * @param voxelZ      structure-local voxel Z
 * @param slot        front/back text slot the player is looking at
 * @param blockStateId registry id of the sign block state
 * @param worldPos    approximate host world position, used only for the
 *                    client-side editor distance check
 * @param signData    captured sign block-entity NBT (including text/wax state)
 */
public record StructureSignOpenPayload(
        long bodyId,
        int voxelX, int voxelY, int voxelZ,
        SignTextSlot slot,
        int blockStateId,
        BlockPos worldPos,
        CompoundTag signData
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructureSignOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_sign_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureSignOpenPayload> STREAM_CODEC =
            StreamCodec.of(StructureSignOpenPayload::write, StructureSignOpenPayload::read);

    public StructureSignOpenPayload {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        slot = slot == null ? SignTextSlot.FRONT : slot;
        worldPos = worldPos == null ? BlockPos.ZERO : worldPos.immutable();
        signData = signData == null ? new CompoundTag() : signData.copy();
    }

    private static void write(RegistryFriendlyByteBuf buf, StructureSignOpenPayload payload) {
        buf.writeVarLong(payload.bodyId());
        buf.writeVarInt(payload.voxelX());
        buf.writeVarInt(payload.voxelY());
        buf.writeVarInt(payload.voxelZ());
        buf.writeEnum(payload.slot());
        buf.writeVarInt(payload.blockStateId());
        buf.writeBlockPos(payload.worldPos());
        buf.writeNbt(payload.signData());
    }

    private static StructureSignOpenPayload read(RegistryFriendlyByteBuf buf) {
        final long bodyId = buf.readVarLong();
        final int x = buf.readVarInt();
        final int y = buf.readVarInt();
        final int z = buf.readVarInt();
        final SignTextSlot slot = buf.readEnum(SignTextSlot.class);
        final int stateId = buf.readVarInt();
        final BlockPos worldPos = buf.readBlockPos();
        final CompoundTag data = buf.readNbt();
        if (data == null) {
            throw new DecoderException("Structure sign open payload has no block-entity data");
        }
        try {
            return new StructureSignOpenPayload(bodyId, x, y, z, slot, stateId, worldPos, data);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure sign open payload", e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
