package io.mikaple.crythst.client.network;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStructureCacheSyncTest {
    private static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier NETHER = Identifier.fromNamespaceAndPath("minecraft", "the_nether");

    @AfterEach
    void clear() {
        ClientStructureCache.clear();
    }

    @Test
    void staleSessionsAreIgnoredAndFullSyncAdoptsTheNewSession() {
        assertFalse(ClientStructureCache.applyPose(new StructurePosePayload(4L, List.of(
                new StructurePosePayload.PoseUpdate(1L, Transform3d.IDENTITY)))));
        assertEquals(0L, ClientStructureCache.activeSyncId());

        assertTrue(ClientStructureCache.apply(new StructureSnapshotPayload(
                4L, OVERWORLD, List.of(), 0, 1, true)));
        assertEquals(4L, ClientStructureCache.activeSyncId());

        assertTrue(ClientStructureCache.applyPose(new StructurePosePayload(4L, List.of())));
        assertFalse(ClientStructureCache.applyPose(new StructurePosePayload(5L, List.of())));
        assertFalse(ClientStructureCache.apply(new StructureSnapshotPayload(
                4L, NETHER, List.of(), 0, 1, false)));

        assertTrue(ClientStructureCache.applyRemove(new StructureRemovePayload(4L, new long[0])));
        assertFalse(ClientStructureCache.applyRemove(new StructureRemovePayload(
                5L, new long[]{1L})));

        assertFalse(ClientStructureCache.apply(new StructureSnapshotPayload(
                5L, OVERWORLD, List.of(), 0, 1, false)));
        assertEquals(4L, ClientStructureCache.activeSyncId());

        assertTrue(ClientStructureCache.apply(new StructureSnapshotPayload(
                6L, OVERWORLD, List.of(), 0, 1, true)));
        assertEquals(6L, ClientStructureCache.activeSyncId());
    }

    @Test
    void statusReportsSessionAndBatch() {
        ClientStructureCache.apply(new StructureSnapshotPayload(12L, OVERWORLD, List.of(), 0, 1, true));
        final String status = ClientStructureCache.statusText();
        assertTrue(status.contains("syncId=12"), status);
        assertTrue(status.contains("lastBatch=0/1"), status);
        assertTrue(status.contains("stale=0"), status);
    }
}
