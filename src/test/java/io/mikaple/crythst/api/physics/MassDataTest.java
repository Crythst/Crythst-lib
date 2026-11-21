package io.mikaple.crythst.api.physics;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MassDataTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void exposesInverseMassAndInverseInertia() {
        final Matrix3d inertia = new Matrix3d().identity()
                .scale(new org.joml.Vector3d(2.0, 4.0, 8.0));
        final MassData data = new MassData(10.0, inertia, new Vector3d(0.1, 0.2, 0.3));

        assertEquals(10.0, data.mass(), EPSILON);
        assertEquals(0.1, data.inverseMass(), EPSILON);
        assertEquals(0.5, data.inverseInertia().m00, EPSILON);
        assertEquals(0.25, data.inverseInertia().m11, EPSILON);
        assertEquals(0.125, data.inverseInertia().m22, EPSILON);
        assertEquals(0.2, data.centerOfMass().y, EPSILON);
    }

    @Test
    void singularInertiaProducesZeroInverse() {
        final MassData data = new MassData(1.0, new Matrix3d().zero(), new Vector3d());
        assertEquals(0.0, data.inverseInertia().m00, EPSILON);
        assertEquals(0.0, data.inverseInertia().m11, EPSILON);
        assertEquals(0.0, data.inverseInertia().m22, EPSILON);
    }
}
