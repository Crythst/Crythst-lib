package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.client.render.portal.PortalMaskPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer/depth policy shared by the mask, composite and depth-restore shaders.
 */
class PortalMaskPolicyTest {
    @Test
    void layerOneKeepsDestinationAndDoesNotRestoreDepthInsideTheMask() {
        assertEquals(1.0f, PortalMaskPolicy.combinedMask(1.0f, 1.0f));
        assertTrue(PortalMaskPolicy.keepsDestination(1.0f, 1.0f));
        assertFalse(PortalMaskPolicy.restoresSnapshotDepth(1.0f, 1.0f));
        assertTrue(PortalMaskPolicy.clearsDestinationDepth(1.0f));
    }

    @Test
    void layerTwoMultipliesTheParentMask() {
        assertEquals(1.0f, PortalMaskPolicy.combinedMask(1.0f, 1.0f));
        assertEquals(0.0f, PortalMaskPolicy.combinedMask(1.0f, 0.0f));
        assertTrue(PortalMaskPolicy.keepsDestination(1.0f, 1.0f));
        assertFalse(PortalMaskPolicy.keepsDestination(1.0f, 0.0f));
        // A child pixel outside the outer opening must restore layer-1 depth.
        assertTrue(PortalMaskPolicy.restoresSnapshotDepth(1.0f, 0.0f));
        // The child still clears its own polygon; the final restore repairs it.
        assertTrue(PortalMaskPolicy.clearsDestinationDepth(1.0f));
        assertFalse(PortalMaskPolicy.clearsDestinationDepth(0.0f));
    }

    @Test
    void emptyMasksRestoreEverythingAndClearNothing() {
        assertFalse(PortalMaskPolicy.keepsDestination(0.0f, 0.0f));
        assertTrue(PortalMaskPolicy.restoresSnapshotDepth(0.0f, 0.0f));
        assertFalse(PortalMaskPolicy.clearsDestinationDepth(0.0f));
    }
}
