package io.mikaple.crythst.network.structure;

import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Wire codec for block-entity updates.
 *
 * <p>Coordinates are structure-local and non-negative. The codec uses VarInts
 * throughout and writes NBT with the vanilla {@link FriendlyByteBuf} helper.
 * Decoding applies the hard per-entry NBT quota, so a malicious payload cannot
 * allocate an unbounded tag before the size check.</p>
 */
final class StructureBlockEntityCodec {
    private StructureBlockEntityCodec() {
    }

    static void writeUpdate(FriendlyByteBuf buf, StructureBlockEntityUpdate update) {
        buf.writeVarLong(update.bodyId());
        buf.writeVarLong(update.baseRevision());
        buf.writeVarLong(update.revision());
        buf.writeBoolean(update.replace());
        buf.writeVarInt(update.partIndex());
        buf.writeVarInt(update.partCount());

        buf.writeVarInt(update.entries().size());
        for (StructureBlockEntity entry : update.entries()) {
            buf.writeVarInt(entry.pos().x());
            buf.writeVarInt(entry.pos().y());
            buf.writeVarInt(entry.pos().z());
            final CompoundTag data = entry.data();
            buf.writeBoolean(data != null);
            if (data != null) {
                buf.writeNbt(data);
            }
            buf.writeVarInt(entry.openCount() + 1);
        }

        buf.writeVarInt(update.removed().size());
        for (StructureVoxelPos pos : update.removed()) {
            buf.writeVarInt(pos.x());
            buf.writeVarInt(pos.y());
            buf.writeVarInt(pos.z());
        }
    }

    static StructureBlockEntityUpdate readUpdate(FriendlyByteBuf buf) {
        final long bodyId = buf.readVarLong();
        final long baseRevision = buf.readVarLong();
        final long revision = buf.readVarLong();
        final boolean replace = buf.readBoolean();
        final int partIndex = buf.readVarInt();
        final int partCount = buf.readVarInt();
        if (partCount < 1 || partCount > StructureSyncLimits.MAX_BLOCK_ENTITY_PARTS) {
            throw new DecoderException("Invalid block-entity part count " + partCount);
        }
        if (partIndex < 0 || partIndex >= partCount) {
            throw new DecoderException("Invalid block-entity part index " + partIndex);
        }

        final int entryCount = buf.readVarInt();
        if (entryCount < 0 || entryCount > StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE) {
            throw new DecoderException("Invalid block-entity entry count " + entryCount);
        }
        final java.util.List<StructureBlockEntity> entries = new java.util.ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            final int x = buf.readVarInt();
            final int y = buf.readVarInt();
            final int z = buf.readVarInt();
            final boolean hasData = buf.readBoolean();
            CompoundTag data = null;
            if (hasData) {
                try {
                    // 26.3 changed readNbt(NbtAccounter) to return Tag.
                    data = (CompoundTag) buf.readNbt(
                            NbtAccounter.create(StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRY_BYTES));
                } catch (RuntimeException e) {
                    throw new DecoderException("Invalid or oversized block-entity NBT", e);
                }
            }
            final int openCount = buf.readVarInt() - 1;
            try {
                entries.add(new StructureBlockEntity(new StructureVoxelPos(x, y, z), data, openCount));
            } catch (IllegalArgumentException e) {
                throw new DecoderException("Invalid structure block entity", e);
            }
        }

        final int removedCount = buf.readVarInt();
        if (removedCount < 0 || removedCount > StructureSyncLimits.MAX_BLOCK_ENTITY_REMOVALS_PER_UPDATE) {
            throw new DecoderException("Invalid block-entity removal count " + removedCount);
        }
        final java.util.List<StructureVoxelPos> removed = new java.util.ArrayList<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            final int x = buf.readVarInt();
            final int y = buf.readVarInt();
            final int z = buf.readVarInt();
            try {
                removed.add(new StructureVoxelPos(x, y, z));
            } catch (IllegalArgumentException e) {
                throw new DecoderException("Invalid removed structure voxel", e);
            }
        }

        try {
            return new StructureBlockEntityUpdate(bodyId, baseRevision, revision, replace,
                    partIndex, partCount, entries, removed);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid block-entity update", e);
        }
    }

    static int estimateUpdateBytes(StructureBlockEntityUpdate update) {
        long size = 10L + 10L + 10L + 1L + 5L + 5L + 5L + 5L;
        for (StructureBlockEntity entry : update.entries()) {
            size += 3L * 5L + 1L + 5L;
            final CompoundTag data = entry.data();
            if (data != null) {
                // Root compound header and value; sizeInBytes is the payload.
                size += 8L + data.sizeInBytes();
            }
        }
        size += (long) update.removed().size() * 3L * 5L;
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    static int estimateEntryBytes(StructureBlockEntity entry) {
        long size = 3L * 5L + 1L + 5L;
        final CompoundTag data = entry.data();
        if (data != null) {
            size += 8L + data.sizeInBytes();
        }
        return (int) Math.min(size, Integer.MAX_VALUE);
    }
}
