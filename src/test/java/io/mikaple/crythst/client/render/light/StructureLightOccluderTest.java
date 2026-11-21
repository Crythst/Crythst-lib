package io.mikaple.crythst.client.render.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLightOccluderTest {
    @Test
    void maskUsesAbsoluteVoxelCoordinatesAndTreatsOutsideAsAir() {
        final StructureLightOccluder.Builder builder =
                StructureLightOccluder.builder(10, 20, 30, 3, 2, 4);

        builder.setSolid(11, 20, 31, true);
        builder.setSolid(12, 21, 33, true);
        // Duplicate writes do not double-count.
        builder.setSolid(11, 20, 31, true);
        builder.setSolid(999, 999, 999, true);

        final StructureLightOccluder mask = builder.build();
        assertTrue(mask.isSolid(11, 20, 31));
        assertTrue(mask.isSolid(12, 21, 33));
        assertFalse(mask.isSolid(10, 20, 30));
        assertFalse(mask.isSolid(11, 20, 32));
        assertFalse(mask.isSolid(13, 21, 33), "outside the mask is air");
        assertFalse(mask.isSolid(9, 20, 30));
        assertTrue(mask.contains(10, 20, 30));
        assertFalse(mask.contains(13, 20, 30));
        assertTrue(mask.solidCount() == 2);
        assertFalse(mask.isEmpty());
    }

    @Test
    void emptyBuilderReturnsSharedEmptyMask() {
        final StructureLightOccluder mask =
                StructureLightOccluder.builder(-5, 0, -5, 4, 4, 4).build();
        assertSame(StructureLightOccluder.EMPTY, mask);
        assertTrue(mask.isEmpty());
        assertFalse(mask.isSolid(-5, 0, -5));
    }

    @Test
    void publishedMaskIsNotAffectedByLaterBuilderWrites() {
        final StructureLightOccluder.Builder builder =
                StructureLightOccluder.builder(0, 0, 0, 2, 2, 2);
        builder.setSolid(0, 0, 0, true);
        final StructureLightOccluder mask = builder.build();
        builder.setSolid(1, 1, 1, true);
        assertTrue(mask.isSolid(0, 0, 0));
        assertFalse(mask.isSolid(1, 1, 1));
    }

    @Test
    void builderValidatesBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> StructureLightOccluder.builder(0, 0, 0, -1, 1, 1));
    }
}
