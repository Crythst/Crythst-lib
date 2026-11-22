package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.client.render.portal.PortalMaskGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalMaskGeometryTest {
    private static PortalShape rectangle() {
        return PortalShape.rectangle(new PortalPlane(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0));
    }

    private static Matrix4f projection() {
        return new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f, true);
    }

    @Test
    void frontFacingOpeningProducesTwoTrianglesAndPixelBounds() {
        final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                rectangle(), projection(), new Vector3d(0.0, 0.0, 5.0), 1920, 1080);
        assertEquals(6, mask.vertexCount());
        assertEquals(2, mask.triangleCount());
        assertTrue(mask.width() > 0);
        assertTrue(mask.height() > 0);
        assertTrue(mask.minX() >= 0 && mask.minX() + mask.width() <= 1920);
        assertTrue(mask.minY() >= 0 && mask.minY() + mask.height() <= 1080);
    }

    @Test
    void polygonOpeningUsesTheRealOutline() {
        final PortalShape triangle = PortalShape.polygon(new PortalPlane(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0), java.util.List.of(
                new org.joml.Vector2d(-1.0, -1.5),
                new org.joml.Vector2d(1.0, -1.5),
                new org.joml.Vector2d(0.0, 1.5)));
        final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                triangle, projection(), new Vector3d(0.0, 0.0, 5.0), 1920, 1080);
        assertEquals(3, mask.vertexCount());
        assertEquals(1, mask.triangleCount());
    }

    @Test
    void behindCameraOpeningIsRejected() {
        final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                rectangle(), projection(), new Vector3d(0.0, 0.0, -5.0), 1920, 1080);
        assertTrue(mask.isEmpty());
    }

    @Test
    void nearPlaneStraddleKeepsAClippedPolygon() {
        // A portal plane along the view axis crosses the eye plane: one edge is
        // in front of the camera and the other is behind it. The mask must be
        // clipped, not discarded and not smeared across the screen.
        final PortalShape sideOn = PortalShape.rectangle(new PortalPlane(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0),
                2.0, 3.0));
        final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                sideOn, projection(), new Vector3d(0.1, 0.0, 0.0), 1920, 1080);
        assertTrue(mask.vertexCount() >= 3);
    }

    @Test
    void fullyOffscreenOpeningClampsToNoPixelsOrAnEmptyBounds() {
        final PortalShape farRight = PortalShape.rectangle(new PortalPlane(
                new Vector3d(1000.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0));
        final PortalMaskGeometry.Mask mask = PortalMaskGeometry.build(
                farRight, projection(), new Vector3d(0.0, 0.0, 5.0), 1920, 1080);
        // NDC is clamped to [-1,1]; a fully off-screen shape collapses to an
        // empty width/height rather than producing a bogus full-screen mask.
        assertTrue(mask.isEmpty() || mask.width() <= 1);
    }
}
