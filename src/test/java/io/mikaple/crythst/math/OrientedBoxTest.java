package io.mikaple.crythst.math;

import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientedBoxTest {
    @Test
    void identityBoxMatchesAabb() {
        final OrientedBox box = OrientedBox.fromAabb(new Aabb(
                new Vector3d(-1, -1, -1), new Vector3d(1, 1, 1)));
        assertTrue(box.contains(new Vector3d(0.9, 0.9, 0.9)));
        assertFalse(box.contains(new Vector3d(1.1, 0.0, 0.0)));
        assertTrue(box.intersects(new Aabb(new Vector3d(0.5, 0.5, 0.5),
                new Vector3d(1.5, 1.5, 1.5))));
    }

    @Test
    void tiltedBoxHasTighterExtentsThanItsAabbButWiderThanAxisAligned() {
        final Quaterniond rotation = new Quaterniond().rotateY(Math.PI / 4.0);
        final OrientedBox tilted = new OrientedBox(new Vector3d(), new Vector3d(1, 1, 1), rotation);
        final Aabb aabb = tilted.toAabb();
        assertEquals(2.0 * Math.sqrt(2.0), aabb.size().x, 1.0e-9);
        assertEquals(2.0, aabb.size().y, 1.0e-9);
        assertTrue(tilted.contains(new Vector3d(1.2, 0.0, 0.0)));
        assertFalse(tilted.contains(new Vector3d(1.45, 0.0, 0.0)));
    }

    @Test
    void satSeparatesDistantBoxes() {
        final OrientedBox a = new OrientedBox(new Vector3d(), new Vector3d(1, 1, 1), new Quaterniond());
        final OrientedBox b = new OrientedBox(new Vector3d(5, 0, 0), new Vector3d(1, 1, 1), new Quaterniond());
        assertFalse(a.intersects(b));
        assertTrue(a.intersects(new OrientedBox(new Vector3d(1.9, 0, 0),
                new Vector3d(1, 1, 1), new Quaterniond())));
    }

    @Test
    void transformRotatesCentreAndOrientation() {
        final Aabb local = new Aabb(new Vector3d(-0.5, -0.5, -0.5), new Vector3d(0.5, 0.5, 0.5));
        final Transform3d pose = Transform3d.of(new Vector3d(10, 2, -3),
                new Quaterniond().rotateZ(Math.PI / 2.0));
        final OrientedBox box = local.toOrientedBox(pose);
        assertEquals(10.0, box.center().x, 1.0e-9);
        assertEquals(2.0, box.center().y, 1.0e-9);
        assertEquals(-3.0, box.center().z, 1.0e-9);
        assertEquals(1.0, box.toAabb().size().x, 1.0e-9);
    }
}
