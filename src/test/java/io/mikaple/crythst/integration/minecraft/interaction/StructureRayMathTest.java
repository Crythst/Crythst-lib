package io.mikaple.crythst.integration.minecraft.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureRayMathTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void hitsFrontFace() {
        final double distance = StructureRayMath.rayBox(
                new org.joml.Vector3d(-2.0, 0.5, 0.5),
                new org.joml.Vector3d(1.0, 0.0, 0.0),
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0,
                10.0);
        assertEquals(2.0, distance, EPSILON);
    }

    @Test
    void missesOutsideSlab() {
        final double distance = StructureRayMath.rayBox(
                new org.joml.Vector3d(-2.0, 2.0, 0.5),
                new org.joml.Vector3d(1.0, 0.0, 0.0),
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0,
                10.0);
        assertEquals(-1.0, distance, EPSILON);
    }

    @Test
    void originInsideBoxReturnsZero() {
        final double distance = StructureRayMath.rayBox(
                new org.joml.Vector3d(0.5, 0.5, 0.5),
                new org.joml.Vector3d(1.0, 0.0, 0.0),
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0,
                10.0);
        assertEquals(0.0, distance, EPSILON);
    }

    @Test
    void maxDistanceRejectsFarHit() {
        final double distance = StructureRayMath.rayBox(
                new org.joml.Vector3d(-2.0, 0.5, 0.5),
                new org.joml.Vector3d(1.0, 0.0, 0.0),
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0,
                1.0);
        assertEquals(-1.0, distance, EPSILON);
    }

    @Test
    void supportsNegativeCoordinates() {
        final double distance = StructureRayMath.rayBox(
                new org.joml.Vector3d(-5.0, -0.5, -0.5),
                new org.joml.Vector3d(1.0, 0.0, 0.0),
                -3.0, -1.0, -1.0,
                -2.0, 0.0, 0.0,
                10.0);
        assertEquals(2.0, distance, EPSILON);
    }
}
