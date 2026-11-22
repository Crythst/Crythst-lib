package io.mikaple.crythst.integration.portal;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTraversalAuthorityTest {
    private static final Vector3d SERVER_CENTRE = new Vector3d(0.0, 64.0, 0.0);

    @Test
    void serverSegmentWinsEvenWhenTheClientSamplesDisagree() {
        assertTrue(PortalTraversalAuthority.accepts(true, false, null, SERVER_CENTRE, 2.5));
        assertTrue(PortalTraversalAuthority.accepts(
                true, true, new Vector3d(100.0, 64.0, 0.0), SERVER_CENTRE, 2.5));
    }

    @Test
    void latentClientPredictionIsAcceptedOnlyNearTheServerBody() {
        assertTrue(PortalTraversalAuthority.accepts(
                false, true, new Vector3d(1.5, 64.0, 0.0), SERVER_CENTRE, 2.5));
        assertFalse(PortalTraversalAuthority.accepts(
                false, true, new Vector3d(2.6, 64.0, 0.0), SERVER_CENTRE, 2.5));
        assertFalse(PortalTraversalAuthority.accepts(
                false, true, new Vector3d(0.0, 64.0, 2.6), SERVER_CENTRE, 2.5));
    }

    @Test
    void noCrossingIsRejected() {
        assertFalse(PortalTraversalAuthority.accepts(false, false, null, SERVER_CENTRE, 2.5));
    }
}
