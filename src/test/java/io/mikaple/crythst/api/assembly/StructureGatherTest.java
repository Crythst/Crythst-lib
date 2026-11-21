package io.mikaple.crythst.api.assembly;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureGatherTest {
    @Test
    void diagonalNeighboursConnectAndDisconnectedBlocksStayOut() {
        final Set<BlockPos> solid = new HashSet<>(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 1, 1),
                new BlockPos(2, 2, 2),
                new BlockPos(4, 0, 0)));
        final StructureGather.GatherResult result = gather(solid, new BlockPos(0, 0, 0), 10, null);

        assertEquals(StructureGather.Status.OK, result.status());
        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 1, 1),
                new BlockPos(2, 2, 2)), result.blocks());
        assertEquals(new StructureGather.Bounds(0, 0, 0, 2, 2, 2), result.bounds());
        assertEquals(27L, result.bounds().volume());
        assertEquals(new BlockPos(0, 0, 0), result.bounds().min());
        assertEquals(new BlockPos(2, 2, 2), result.bounds().max());
    }

    @Test
    void outputOrderIsDeterministicRegardlessOfInputOrder() {
        final Set<BlockPos> solid = new HashSet<>(List.of(
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(0, 1, 0),
                new BlockPos(0, 0, 0)));
        final StructureGather.GatherResult result = gather(solid, new BlockPos(0, 0, 0), 10, null);

        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(0, 1, 0),
                new BlockPos(1, 0, 0)), result.blocks());
    }

    @Test
    void missingOriginReportsNoBlocks() {
        final StructureGather.GatherResult result = gather(
                Set.of(new BlockPos(1, 0, 0)), new BlockPos(0, 0, 0), 10, null);

        assertEquals(StructureGather.Status.NO_BLOCKS, result.status());
        assertFalse(result.success());
        assertTrue(result.blocks().isEmpty());
        assertNull(result.bounds());
    }

    @Test
    void frontierFilterStopsTheFillThroughRejectedBlocks() {
        final Set<BlockPos> solid = new HashSet<>(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0)));
        final Predicate<BlockPos> filter = pos -> pos.getX() < 1;
        final StructureGather.GatherResult result = gather(
                solid, new BlockPos(0, 0, 0), 10, filter);

        assertEquals(List.of(new BlockPos(0, 0, 0)), result.blocks());
    }

    @Test
    void regionOverLimitReportsTooManyWithBoundedPartialList() {
        final Set<BlockPos> solid = new HashSet<>(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0)));
        final StructureGather.GatherResult result = gather(solid, new BlockPos(0, 0, 0), 2, null);

        assertEquals(StructureGather.Status.TOO_MANY, result.status());
        assertEquals(2, result.blocks().size());
        assertEquals(2, result.limit());
        assertFalse(result.success());
    }

    @Test
    void boundsUseInclusiveBlocksAndExclusivePhysicsMaximum() {
        final StructureGather.Bounds bounds = new StructureGather.Bounds(-2, 4, 7, 1, 6, 9);

        assertTrue(bounds.contains(-2, 4, 7));
        assertTrue(bounds.contains(1, 6, 9));
        assertFalse(bounds.contains(2, 6, 9));
        assertEquals(4 * 3 * 3L, bounds.volume());
        assertEquals(new io.mikaple.crythst.physics.voxel.IntBounds3(-2, 4, 7, 2, 7, 10),
                bounds.toIntBounds3());
    }

    private static StructureGather.GatherResult gather(Set<BlockPos> solid, BlockPos origin,
                                                       int maxBlocks,
                                                       Predicate<BlockPos> filter) {
        return StructureGather.gather(origin, maxBlocks, filter, solid::contains);
    }
}
