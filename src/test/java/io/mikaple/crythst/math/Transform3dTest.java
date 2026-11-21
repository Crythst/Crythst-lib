package io.mikaple.crythst.math;

import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Transform3dTest {
    @Test
    void composeAndInverseRoundTrip() {
        final Transform3d first = Transform3d.of(new Vector3d(3, -2, 7),
                new Quaterniond().rotateX(0.4));
        final Transform3d second = Transform3d.of(new Vector3d(-1, 2, 0.5),
                new Quaterniond().rotateY(-0.7));
        final Vector3d point = new Vector3d(1.5, -4, 2);
        final Vector3d transformed = first.compose(second).transformPoint(point);
        final Vector3d restored = first.compose(second).inverseTransformPoint(transformed);
        assertEquals(point.x, restored.x, 1.0e-9);
        assertEquals(point.y, restored.y, 1.0e-9);
        assertEquals(point.z, restored.z, 1.0e-9);
    }

    @Test
    void interpolationHitsBothEnds() {
        final Transform3d from = Transform3d.of(new Vector3d());
        final Transform3d to = Transform3d.of(new Vector3d(10, 0, 0));
        assertEquals(0.0, from.interpolate(to, 0.0).position().x, 1.0e-9);
        assertEquals(10.0, from.interpolate(to, 1.0).position().x, 1.0e-9);
        assertEquals(5.0, from.interpolate(to, 0.5).position().x, 1.0e-9);
    }
}
