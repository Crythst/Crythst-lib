package io.mikaple.crythst.client.network;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructureBlockEntity;
import io.mikaple.crythst.network.structure.StructureBlockEntityPayload;
import io.mikaple.crythst.network.structure.StructureBlockEntityUpdate;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.network.structure.StructureSyncLimits;
import io.mikaple.crythst.network.structure.StructureSyncProtocol;
import io.mikaple.crythst.network.structure.StructureVoxelPos;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBlockEntityCacheTest {
    private static final Identifier OVERWORLD =
            Identifier.fromNamespaceAndPath("minecraft", "overworld");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearCache() {
        ClientStructureCache.clear();
    }

    @Test
    void appliesFullAnimationRemovalAndBodyRemoval() {
        applyGeometry(1L, 4);
        final CompoundTag chest = chestTag("minecraft:chest");

        assertTrue(ClientStructureCache.applyBlockEntities(new StructureBlockEntityPayload(100L, List.of(
                new StructureBlockEntityUpdate(1L, 0L, 1L, true, 0, 1,
                        List.of(new StructureBlockEntity(new StructureVoxelPos(0, 0, 0), chest, 1)),
                        List.of())))));
        ClientBlockEntitySnapshot snapshot = ClientStructureCache.blockEntities(1L);
        assertNotNull(snapshot);
        assertEquals(1L, snapshot.revision());
        assertEquals(1, snapshot.size());
        assertEquals("minecraft:chest",
                snapshot.get(0, 0, 0).data().getStringOr("id", ""));
        assertEquals(1, snapshot.get(0, 0, 0).openCount());

        // Animation-only: keep the NBT, close the lid.
        assertTrue(ClientStructureCache.applyBlockEntities(new StructureBlockEntityPayload(100L, List.of(
                new StructureBlockEntityUpdate(1L, 1L, 2L, false, 0, 1,
                        List.of(new StructureBlockEntity(new StructureVoxelPos(0, 0, 0), null, 0)),
                        List.of())))));
        snapshot = ClientStructureCache.blockEntities(1L);
        assertEquals(2L, snapshot.revision());
        assertEquals(1L, snapshot.dataRevision(),
                "animation-only updates must not invalidate synthetic block entities");
        assertEquals(0, snapshot.get(0, 0, 0).openCount());
        assertEquals("minecraft:chest",
                snapshot.get(0, 0, 0).data().getStringOr("id", ""));

        // Break the chest: the removal must drop the stale entry.
        assertTrue(ClientStructureCache.applyBlockEntities(new StructureBlockEntityPayload(100L, List.of(
                new StructureBlockEntityUpdate(1L, 2L, 3L, false, 0, 1,
                        List.of(), List.of(new StructureVoxelPos(0, 0, 0)))))));
        assertNotNull(ClientStructureCache.blockEntities(1L));
        assertEquals(0, ClientStructureCache.blockEntities(1L).size());

        // Removing the body drops all NBT.
        assertTrue(ClientStructureCache.applyRemove(
                new StructureRemovePayload(100L, new long[]{1L})));
        assertNull(ClientStructureCache.blockEntities(1L));
        assertEquals(0, ClientStructureCache.blockEntityBodyCount());
    }

    @Test
    void dirtyUpdateWithWrongBaseDropsStaleDataAndRequestsResync() {
        applyGeometry(2L, 2);
        ClientStructureCache.applyBlockEntities(new StructureBlockEntityPayload(100L, List.of(
                new StructureBlockEntityUpdate(2L, 0L, 1L, true, 0, 1,
                        List.of(new StructureBlockEntity(new StructureVoxelPos(0, 0, 0),
                                chestTag("minecraft:chest"), -1)),
                        List.of()))));
        assertNotNull(ClientStructureCache.blockEntities(2L));
        ClientStructureCache.consumeBlockEntityResyncRequest();

        // baseRevision 0 no longer matches the cached revision 1.
        ClientStructureCache.applyBlockEntities(new StructureBlockEntityPayload(100L, List.of(
                new StructureBlockEntityUpdate(2L, 0L, 2L, false, 0, 1,
                        List.of(new StructureBlockEntity(new StructureVoxelPos(0, 0, 0),
                                chestTag("minecraft:trapped_chest"), -1)),
                        List.of()))));

        assertNull(ClientStructureCache.blockEntities(2L));
        assertTrue(ClientStructureCache.consumeBlockEntityResyncRequest());
        assertFalse(ClientStructureCache.consumeBlockEntityResyncRequest());
    }

    @Test
    void fragmentedFullReplacementCommitsOnlyOnLastPart() {
        applyGeometry(3L, 2);
        final List<StructureBlockEntity> entries = new ArrayList<>();
        for (int i = 0; i < StructureSyncLimits.MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE + 2; i++) {
            entries.add(new StructureBlockEntity(new StructureVoxelPos(i, 0, 0),
                    chestTag("minecraft:chest"), -1));
        }
        final List<StructureBlockEntityUpdate> parts =
                StructureSyncProtocol.fragmentBlockEntityUpdate(
                        3L, 0L, 1L, true, entries, List.of());
        assertTrue(parts.size() >= 2);
        for (int i = 0; i < parts.size() - 1; i++) {
            ClientStructureCache.applyBlockEntities(
                    new StructureBlockEntityPayload(100L, List.of(parts.get(i))));
            assertNull(ClientStructureCache.blockEntities(3L),
                    "a partial replacement must not expose half a container map");
        }

        ClientStructureCache.applyBlockEntities(
                new StructureBlockEntityPayload(100L, List.of(parts.getLast())));
        assertNotNull(ClientStructureCache.blockEntities(3L));
        assertEquals(entries.size(), ClientStructureCache.blockEntities(3L).size());
    }

    @Test
    void fullSnapshotResetDropsBlockEntities() {
        applyGeometry(4L, 1);
        ClientStructureCache.applyBlockEntities(new StructureBlockEntityPayload(100L, List.of(
                new StructureBlockEntityUpdate(4L, 0L, 1L, true, 0, 1,
                        List.of(new StructureBlockEntity(new StructureVoxelPos(0, 0, 0),
                                chestTag("minecraft:chest"), -1)),
                        List.of()))));
        assertNotNull(ClientStructureCache.blockEntities(4L));

        applyGeometry(4L, 1);
        assertNull(ClientStructureCache.blockEntities(4L));
    }

    private static void applyGeometry(long bodyId, int sizeX) {
        final int[] cells = new int[sizeX];
        java.util.Arrays.fill(cells, 1);
        final StructureSnapshot wire = new StructureSnapshot(
                bodyId,
                Transform3d.IDENTITY,
                new IntBounds3(0, 0, 0, sizeX, 1, 1),
                new int[]{Block.getId(Blocks.STONE.defaultBlockState())},
                cells,
                1.0, 1.0, 10.0,
                new Vector3d(0.5, 0.5, 0.5));
        ClientStructureCache.apply(new StructureSnapshotPayload(
                100L, OVERWORLD, List.of(wire), 0, 1, true));
    }

    private static CompoundTag chestTag(String id) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("x", 0);
        tag.putInt("y", 0);
        tag.putInt("z", 0);
        return tag;
    }
}
