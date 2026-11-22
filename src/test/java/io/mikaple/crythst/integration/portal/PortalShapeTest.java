package io.mikaple.crythst.integration.portal;

import org.joml.Vector2d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalShapeTest {
    private static final double EPS = 1.0e-9;

    private static PortalPlane plane() {
        return new PortalPlane(
                new Vector3d(1.0, 64.0, -2.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    @Test
    void rectangleContainsLocalAndWorldPoints() {
        final PortalShape shape = PortalShape.rectangle(plane());
        assertTrue(shape.rectangular());
        assertTrue(shape.containsLocal(0.0, 0.0));
        assertTrue(shape.containsLocal(1.0, 1.5));
        assertTrue(shape.containsLocal(1.0000001, -1.5));
        assertFalse(shape.containsLocal(1.01, 0.0));
        assertFalse(shape.containsLocal(0.0, 1.51));

        final Vector3d world = plane().worldPoint(new Vector3d(0.4, -0.7, 0.2));
        assertTrue(shape.contains(world, 0.35));
        assertFalse(shape.contains(plane().worldPoint(new Vector3d(2.5, 0.0, 0.0)), 0.35));
    }

    @Test
    void polygonSupportsNonRectangularOpenings() {
        final PortalShape shape = PortalShape.polygon(plane(), List.of(
                new Vector2d(0.0, 1.5),
                new Vector2d(1.0, 0.0),
                new Vector2d(0.0, -1.5),
                new Vector2d(-1.0, 0.0)));
        assertFalse(shape.rectangular());
        assertTrue(shape.containsLocal(0.0, 0.0));
        assertTrue(shape.containsLocal(0.4, 0.4));
        assertFalse(shape.containsLocal(0.9, 0.9));
        assertFalse(shape.containsLocal(1.1, 0.0));
        assertEquals(4, shape.worldOutline().size());
        assertEquals(2.0, shape.bounds().max().x - shape.bounds().min().x, EPS);
        assertEquals(3.0, shape.bounds().max().y - shape.bounds().min().y, EPS);
    }

    @Test
    void rejectsDegenerateOutlines() {
        assertThrows(IllegalArgumentException.class, () -> PortalShape.polygon(
                plane(), List.of(new Vector2d(0.0, 0.0), new Vector2d(1.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> PortalShape.polygon(
                plane(), List.of(new Vector2d(0.0, 0.0), new Vector2d(1.0, 0.0),
                        new Vector2d(2.0, 0.0))));
    }

    @Test
    void movingShapeKeepsLocalOutline() {
        final PortalShape shape = PortalShape.rectangle(plane());
        final PortalPlane moved = new PortalPlane(
                new Vector3d(5.0, 70.0, 8.0),
                new Vector3d(0.0, 0.0, -1.0),
                new Vector3d(-1.0, 0.0, 0.0),
                2.0, 3.0);
        final PortalShape movedShape = shape.withPlane(moved);
        assertTrue(movedShape.contains(moved.worldPoint(new Vector3d(0.0, 0.0, 0.0)), 0.1));
        assertTrue(movedShape.containsLocal(0.9, 1.0));
    }

    @Test
    void crossingUsesPolygonNotBoundingBox() {
        final PortalShape diamond = PortalShape.polygon(plane(), List.of(
                new Vector2d(0.0, 1.5),
                new Vector2d(1.0, 0.0),
                new Vector2d(0.0, -1.5),
                new Vector2d(-1.0, 0.0)));
        // Bounding-box corner: x=0.95, y=1.4 is outside the diamond even
        // though it is inside the rectangle that bounds it.
        assertFalse(PortalCrossing.crosses(diamond,
                plane().worldPoint(new Vector3d(0.95, 1.4, 1.0)),
                plane().worldPoint(new Vector3d(0.95, 1.4, -1.0))));
        assertTrue(PortalCrossing.crosses(diamond,
                plane().worldPoint(new Vector3d(0.0, 0.0, 1.0)),
                plane().worldPoint(new Vector3d(0.0, 0.0, -1.0))));
    }
}
