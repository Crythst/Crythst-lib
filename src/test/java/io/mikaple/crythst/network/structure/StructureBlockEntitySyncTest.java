package io.mikaple.crythst.network.structure;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureBlockEntitySyncTest {
    @Test
    void payloadRoundTripsNbtAnimationAndRemovals() {
        final CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 3);
        chest.putInt("y", 4);
        chest.putInt("z", 5);
        final ListTag items = new ListTag();
        final CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:stone");
        item.putByte("Count", (byte) 64);
        items.add(item);
        chest.put("Items", items);

        final StructureBlockEntityUpdate full = new StructureBlockEntityUpdate(
                9L, 0L, 1L, true, 0, 1,
                List.of(new StructureBlockEntity(new StructureVoxelPos(3, 4, 5), chest, 2)),
                List.of());
        final StructureBlockEntityUpdate animationOnly = new StructureBlockEntityUpdate(
                9L, 1L, 2L, false, 0, 1,
                List.of(new StructureBlockEntity(new StructureVoxelPos(3, 4, 5), null, 0)),
                List.of(new StructureVoxelPos(4, 4, 5)));
        final StructureBlockEntityPayload payload =
                new StructureBlockEntityPayload(77L, List.of(full, animationOnly));

        final StructureBlockEntityPayload decoded = roundTrip(
                StructureBlockEntityPayload.STREAM_CODEC, payload);

        assertEquals(77L, decoded.syncId());
        assertEquals(2, decoded.updates().size());
        final StructureBlockEntityUpdate decodedFull = decoded.updates().get(0);
        assertTrue(decodedFull.replace());
        assertEquals(1L, decodedFull.revision());
        assertEquals(1, decodedFull.entries().size());
        final StructureBlockEntity decodedEntry = decodedFull.entries().getFirst();
        assertEquals(new StructureVoxelPos(3, 4, 5), decodedEntry.pos());
        assertEquals(2, decodedEntry.openCount());
        assertNotNull(decodedEntry.data());
        assertEquals("minecraft:chest", decodedEntry.data().getStringOr("id", ""));
        assertEquals(1, decodedEntry.data().getListOrEmpty("Items").size());

        final StructureBlockEntityUpdate decodedAnimation = decoded.updates().get(1);
        assertFalse(decodedAnimation.replace());
        assertEquals(1L, decodedAnimation.baseRevision());
        assertNull(decodedAnimation.entries().getFirst().data());
        assertEquals(0, decodedAnimation.entries().getFirst().openCount());
        assertEquals(List.of(new StructureVoxelPos(4, 4, 5)), decodedAnimation.removed());
    }

    @Test
    void deltaSendsNbtOnlyWhenNbtChanged() {
        final StructureVoxelPos unchanged = new StructureVoxelPos(0, 0, 0);
        final StructureVoxelPos animation = new StructureVoxelPos(1, 0, 0);
        final StructureVoxelPos changed = new StructureVoxelPos(2, 0, 0);
        final StructureVoxelPos removed = new StructureVoxelPos(3, 0, 0);
        final StructureVoxelPos added = new StructureVoxelPos(4, 0, 0);

        final Map<StructureVoxelPos, StructureBlockEntityDelta.Value> before = new LinkedHashMap<>();
        before.put(unchanged, value("stone", 0));
        before.put(animation, value("chest", 1));
        before.put(changed, value("old", 0));
        before.put(removed, value("removed", 0));

        final Map<StructureVoxelPos, StructureBlockEntityDelta.Value> after = new LinkedHashMap<>();
        after.put(unchanged, value("stone", 0));
        after.put(animation, value("chest", 2));
        after.put(changed, value("new", 0));
        after.put(added, value("added", 0));

        final StructureBlockEntityDelta.Result delta =
                StructureBlockEntityDelta.compute(before, after);

        assertEquals(3, delta.changed().size());
        assertEquals(List.of(removed), delta.removed());
        for (StructureBlockEntity entry : delta.changed()) {
            if (entry.pos().equals(animation)) {
                assertNull(entry.data(), "animation-only update must not resend NBT");
                assertEquals(2, entry.openCount());
            } else if (entry.pos().equals(changed)) {
                assertEquals("new", entry.data().getStringOr("id", ""));
            } else if (entry.pos().equals(added)) {
                assertEquals("added", entry.data().getStringOr("id", ""));
            } else {
                throw new AssertionError("Unexpected changed entry " + entry.pos());
            }
        }
    }

    @Test
    void fragmentationSplitsLargeBodiesAndStaysWithinUpdateCap() {
        final List<StructureBlockEntity> entries = new ArrayList<>();
        for (int i = 0; i < StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE + 3; i++) {
            entries.add(new StructureBlockEntity(new StructureVoxelPos(i, 0, 0),
                    value("stone", -1).data(), -1));
        }
        final List<StructureBlockEntityUpdate> parts = StructureSyncProtocol.fragmentBlockEntityUpdate(
                5L, 0L, 1L, true, entries, List.of());

        assertTrue(parts.size() >= 2);
        assertEquals(0, parts.get(0).partIndex());
        assertEquals(parts.size(), parts.get(0).partCount());
        assertTrue(parts.get(0).replace());
        assertTrue(parts.get(1).replace());
        int total = 0;
        int partIndex = 0;
        for (StructureBlockEntityUpdate part : parts) {
            assertEquals(partIndex++, part.partIndex());
            assertTrue(part.entries().size()
                    <= StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE);
            assertTrue(StructureSyncProtocol.estimateBlockEntityUpdateBytes(part)
                    <= StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES);
            total += part.entries().size();
        }
        assertEquals(entries.size(), total);
    }

    @Test
    void oversizedEntryIsRejectedGracefully() {
        final CompoundTag huge = new CompoundTag();
        huge.putString("payload", "x".repeat(StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATE_BYTES + 1024));
        final StructureBlockEntity entry = new StructureBlockEntity(
                new StructureVoxelPos(0, 0, 0), huge, -1);

        assertThrows(StructureTooLargeException.class,
                () -> StructureSyncProtocol.fragmentBlockEntityUpdate(
                        1L, 0L, 1L, true, List.of(entry), List.of()));
        final StructureBlockEntityUpdate update = new StructureBlockEntityUpdate(
                1L, 0L, 1L, true, 0, 1, List.of(entry), List.of());
        assertThrows(StructureTooLargeException.class,
                () -> StructureSyncProtocol.batchBlockEntityUpdates(List.of(update)));
    }

    @Test
    void updateRejectsInvalidPartsAndDuplicatePositions() {
        assertThrows(IllegalArgumentException.class,
                () -> new StructureBlockEntityUpdate(1L, 0L, 1L, false, 2, 2, List.of(), List.of()));
        // A full replacement carries replace=true on every fragment so the
        // client can distinguish continuation parts from dirty fragmentation.
        new StructureBlockEntityUpdate(1L, 0L, 1L, true, 1, 2, List.of(), List.of());
        final StructureBlockEntity entry = new StructureBlockEntity(
                new StructureVoxelPos(0, 0, 0), value("stone", -1).data(), -1);
        assertThrows(IllegalArgumentException.class,
                () -> new StructureBlockEntityUpdate(1L, 0L, 1L, false, 0, 1,
                        List.of(entry, entry), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StructureBlockEntityPayload(1L, java.util.Collections.nCopies(
                        StructureSyncLimits.MAX_BLOCK_ENTITY_UPDATES_PER_PAYLOAD + 1,
                        new StructureBlockEntityUpdate(1L, 0L, 1L, false, 0, 1,
                                List.of(), List.of()))));
    }

    @Test
    void decodeRejectsAnotherProtocolVersion() {
        final ByteBuf output = Unpooled.buffer();
        try {
            final FriendlyByteBuf writer = new FriendlyByteBuf(output);
            writer.writeVarInt(StructureSyncLimits.PROTOCOL_VERSION + 1);
            writer.writeVarLong(1L);
            writer.writeVarInt(0);
            final ByteBuf input = Unpooled.wrappedBuffer(
                    java.util.Arrays.copyOf(output.array(), writer.writerIndex()));
            try {
                assertThrows(io.netty.handler.codec.DecoderException.class,
                        () -> StructureBlockEntityPayload.STREAM_CODEC.decode(
                                new FriendlyByteBuf(input)));
            } finally {
                input.release();
            }
        } finally {
            output.release();
        }
    }

    private static StructureBlockEntityDelta.Value value(String id, int openCount) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return new StructureBlockEntityDelta.Value(tag, openCount);
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        final ByteBuf output = Unpooled.buffer();
        try {
            final FriendlyByteBuf writer = new FriendlyByteBuf(output);
            codec.encode(writer, payload);
            final byte[] bytes = new byte[writer.writerIndex()];
            writer.getBytes(0, bytes);

            final ByteBuf input = Unpooled.wrappedBuffer(bytes);
            try {
                return codec.decode(new FriendlyByteBuf(input));
            } finally {
                input.release();
            }
        } finally {
            output.release();
        }
    }
}
