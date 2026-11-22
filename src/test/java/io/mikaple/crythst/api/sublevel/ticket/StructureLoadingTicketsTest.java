package io.mikaple.crythst.api.sublevel.ticket;

import io.mikaple.crythst.math.Aabb;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLoadingTicketsTest {
    @Test
    void coveredChunksCoverTheWholeWorldBoundsAndRadius() {
        final Aabb bounds = Aabb.of(new Vector3d(0.5, 0.0, 0.5),
                new Vector3d(17.5, 4.0, 33.5));

        final List<ChunkPos> chunks = StructureLoadingTickets.coveredChunks(bounds, 0);
        assertEquals(6, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(0, 0)));
        assertTrue(chunks.contains(new ChunkPos(1, 0)));
        assertTrue(chunks.contains(new ChunkPos(0, 2)));
        assertTrue(chunks.contains(new ChunkPos(1, 2)));

        final List<ChunkPos> expanded = StructureLoadingTickets.coveredChunks(bounds, 1);
        assertEquals(4 * 5, expanded.size());
        assertTrue(expanded.contains(new ChunkPos(-1, -1)));
        assertTrue(expanded.contains(new ChunkPos(2, 3)));
    }

    @Test
    void ticketReferencesAreCountedAndRemovedOnTheLastRelease() {
        final TicketReferenceLedger ledger = new TicketReferenceLedger();
        final long chunk = new ChunkPos(4, -2).pack();
        assertTrue(ledger.retain(chunk));
        assertFalse(ledger.retain(chunk));
        assertEquals(2, ledger.count(chunk));
        assertEquals(1, ledger.size());

        assertFalse(ledger.release(chunk));
        assertTrue(ledger.release(chunk));
        assertEquals(0, ledger.count(chunk));
        assertEquals(0, ledger.size());
        assertFalse(ledger.release(chunk));
    }

    @Test
    void coveredChunksRejectNegativeRadiusAndNegativeCoordinatesWork() {
        final Aabb bounds = Aabb.of(new Vector3d(-1.0, 0.0, -1.0),
                new Vector3d(1.0, 1.0, 1.0));
        final List<ChunkPos> chunks = StructureLoadingTickets.coveredChunks(bounds, 0);
        assertEquals(4, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(-1, -1)));
        assertTrue(chunks.contains(new ChunkPos(0, 0)));

        assertThrows(IllegalArgumentException.class,
                () -> StructureLoadingTickets.coveredChunks(bounds, -1));
    }
}
