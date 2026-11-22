package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.client.render.portal.PortalRecursion;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRecursionTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void layerIncrementsUntilLimit() {
        assertEquals(2, PortalRecursion.nextLayer(1, 2, B, Set.of(A)));
        assertEquals(0, PortalRecursion.nextLayer(2, 2, B, Set.of(A)));
        assertEquals(3, PortalRecursion.nextLayer(2, 3, B, Set.of(A)));
    }

    @Test
    void ancestorCannotBeReentered() {
        assertEquals(0, PortalRecursion.nextLayer(1, 2, A, Set.of(A)));
        assertEquals(0, PortalRecursion.nextLayer(1, 3, A, Set.of(A, B)));
    }

    @Test
    void exitSurfaceCannotRenderTheReverseLinkInsideItsOwnOpening() {
        final UUID unrelatedPortal = UUID.randomUUID();
        assertEquals(0, PortalRecursion.nextLayer(1, 2, B, Set.of(A), B));
        assertEquals(0, PortalRecursion.nextLayer(1, 2, A, Set.of(B), A));
        assertEquals(2, PortalRecursion.nextLayer(1, 2, unrelatedPortal, Set.of(A), B));
    }

    @Test
    void invalidLayersAreRejected() {
        assertEquals(0, PortalRecursion.nextLayer(0, 2, B, Set.of()));
        assertEquals(0, PortalRecursion.nextLayer(1, 0, B, Set.of()));
        assertTrue(PortalRecursion.permits(1, 2));
        assertTrue(PortalRecursion.permits(2, 2));
        assertFalse(PortalRecursion.permits(3, 2));
    }
}
