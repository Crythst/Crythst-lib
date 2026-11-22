package io.mikaple.crythst.integration.portal;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCrossingSweepTest {
    private static PortalPlane exit() {
        return new PortalPlane(
                new Vector3d(20.0, 70.0, -5.0),
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0),
                2.0, 3.0);
    }

    private static PortalShape diamond() {
        final PortalPlane plane = new PortalPlane(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
        return PortalShape.polygon(plane, java.util.List.of(
                new org.joml.Vector2d(0.0, 1.5),
                new org.joml.Vector2d(1.0, 0.0),
                new org.joml.Vector2d(0.0, -1.5),
                new org.joml.Vector2d(-1.0, 0.0)));
    }

    @Test
    void projectileSegmentCrossesInsidePolygon() {
        assertTrue(PortalTeleportMath.sweptCrossing(diamond(),
                new Vector3d(0.0, 64.0, 40.0),
                new Vector3d(0.0, 64.0, -40.0)));
    }

    @Test
    void fastSegmentOutsidePolygonDoesNotCross() {
        assertFalse(PortalTeleportMath.sweptCrossing(diamond(),
                new Vector3d(0.95, 65.4, 40.0),
                new Vector3d(0.95, 65.4, -40.0)));
    }

    @Test
    void exitSafeZoneRecognisesLeavingVelocity() {
        final PortalPlane exit = exit();
        final Vector3d onExit = exit.worldPoint(new Vector3d(0.0, 0.0, 0.0));
        assertTrue(PortalTeleportMath.insideExitSafeZone(
                exit, onExit, new Vector3d(1.0, 0.0, 0.0), 0.35));
        assertFalse(PortalTeleportMath.insideExitSafeZone(
                exit, onExit, new Vector3d(-1.0, 0.0, 0.0), 0.35));
    }
}
