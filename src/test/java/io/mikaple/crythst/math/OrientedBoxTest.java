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

    @Test
    void axesAndExtentsFollowTheQuaternionForGeneralRotations() {
        final Quaterniond rotation = new Quaterniond()
                .rotateX(0.41)
                .rotateY(0.93)
                .rotateZ(-0.37);
        final Vector3d half = new Vector3d(1.0, 2.0, 3.0);
        final OrientedBox box = new OrientedBox(new Vector3d(4.0, -2.0, 7.0), half, rotation);

        final Vector3d[] expectedAxes = {
                new Vector3d(1, 0, 0).rotate(rotation),
                new Vector3d(0, 1, 0).rotate(rotation),
                new Vector3d(0, 0, 1).rotate(rotation)
        };
        final Vector3d[] actualAxes = box.axes();
        for (int i = 0; i < 3; i++) {
            assertEquals(expectedAxes[i].x, actualAxes[i].x, 1.0e-12);
            assertEquals(expectedAxes[i].y, actualAxes[i].y, 1.0e-12);
            assertEquals(expectedAxes[i].z, actualAxes[i].z, 1.0e-12);
        }

        // Compare the AABB of the oriented box with the AABB of its rotated
        // corners. A transposed axes() implementation under-estimates this
        // for general rotations and makes collision broad phases miss.
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        final Vector3d center = box.center();
        for (int i = 0; i < 8; i++) {
            final Vector3d corner = new Vector3d(center);
            corner.add(new Vector3d(expectedAxes[0]).mul((i & 1) == 0 ? half.x : -half.x));
            corner.add(new Vector3d(expectedAxes[1]).mul((i & 2) == 0 ? half.y : -half.y));
            corner.add(new Vector3d(expectedAxes[2]).mul((i & 4) == 0 ? half.z : -half.z));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        final Aabb aabb = box.toAabb();
        assertEquals(minX, aabb.min().x, 1.0e-12);
        assertEquals(minY, aabb.min().y, 1.0e-12);
        assertEquals(minZ, aabb.min().z, 1.0e-12);
        assertEquals(maxX, aabb.max().x, 1.0e-12);
        assertEquals(maxY, aabb.max().y, 1.0e-12);
        assertEquals(maxZ, aabb.max().z, 1.0e-12);
    }
}
