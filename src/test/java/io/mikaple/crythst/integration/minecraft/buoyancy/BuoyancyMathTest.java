package io.mikaple.crythst.integration.minecraft.buoyancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuoyancyMathTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void submergedFractionIsClampedToValidRange() {
        assertEquals(0.0, BuoyancyMath.submergedFraction(0, 27), EPSILON);
        assertEquals(0.25, BuoyancyMath.submergedFraction(2, 8), EPSILON);
        assertEquals(1.0, BuoyancyMath.submergedFraction(8, 8), EPSILON);
        assertEquals(1.0, BuoyancyMath.submergedFraction(9, 8), EPSILON);
        assertEquals(0.0, BuoyancyMath.submergedFraction(-1, 8), EPSILON);
        assertEquals(0.0, BuoyancyMath.submergedFraction(1, 0), EPSILON);
        assertEquals(1.0, BuoyancyMath.clampFraction(2.5), EPSILON);
        assertEquals(0.0, BuoyancyMath.clampFraction(Double.NaN), EPSILON);
    }

    @Test
    void submergedVolumeScalesSolidVolume() {
        assertEquals(0.0, BuoyancyMath.submergedVolume(2.0, 0.0), EPSILON);
        assertEquals(1.0, BuoyancyMath.submergedVolume(2.0, 0.5), EPSILON);
        assertEquals(2.0, BuoyancyMath.submergedVolume(2.0, 1.0), EPSILON);
        assertEquals(2.0, BuoyancyMath.submergedVolume(2.0, 3.0), EPSILON);
        assertEquals(0.0, BuoyancyMath.submergedVolume(-2.0, 1.0), EPSILON);
        assertEquals(0.0, BuoyancyMath.submergedVolume(2.0, Double.NaN), EPSILON);
    }

    @Test
    void displacedMassUsesWaterDensity() {
        assertEquals(1000.0, BuoyancyMath.displacedMass(1000.0, 1.0), EPSILON);
        assertEquals(500.0, BuoyancyMath.displacedMass(1000.0, 0.5), EPSILON);
        assertEquals(0.0, BuoyancyMath.displacedMass(0.0, 1.0), EPSILON);
        assertEquals(0.0, BuoyancyMath.displacedMass(1000.0, -1.0), EPSILON);

        assertEquals(9810.0, BuoyancyMath.buoyantForce(1000.0, 1.0, 9.81), EPSILON);
        assertEquals(0.0, BuoyancyMath.buoyantForce(1000.0, 0.0, 9.81), EPSILON);
        assertEquals(0.0, BuoyancyMath.buoyantForce(1000.0, 1.0, 0.0), EPSILON);
    }

    @Test
    void fullySubmergedEndStoneGetsPositiveLift() {
        // The default mapper gives end stone 500 kg per one-cubic-metre block.
        final double waterDensity = 1000.0;
        final double submergedVolume = 1.0;
        final double bodyMass = 500.0;
        final double gravity = 9.81;

        assertTrue(BuoyancyMath.floats(bodyMass, submergedVolume, waterDensity),
                "end stone density (500 kg/m³) must be below water density");
        // Bullet applies weight itself, so the manager applies the full
        // displaced-water force. Its net result matches netBuoyantForce.
        assertEquals(9810.0, BuoyancyMath.buoyantForce(
                waterDensity, submergedVolume, gravity), EPSILON);
        assertEquals(4905.0, BuoyancyMath.netBuoyantForce(
                waterDensity, submergedVolume, bodyMass, gravity), EPSILON);
        assertEquals(bodyMass * gravity,
                BuoyancyMath.buoyantForce(waterDensity, submergedVolume, gravity)
                        - BuoyancyMath.netBuoyantForce(
                        waterDensity, submergedVolume, bodyMass, gravity), EPSILON);
    }

    @Test
    void zeroAndOverDensityBodiesAreClampedToNoLift() {
        // A dense body must not receive a downward "negative buoyancy" force.
        assertEquals(0.0, BuoyancyMath.netBuoyantForce(1000.0, 1.0, 2400.0, 9.81), EPSILON);
        // Neutral density gives exactly zero net force.
        assertEquals(0.0, BuoyancyMath.netBuoyantForce(1000.0, 1.0, 1000.0, 9.81), EPSILON);
        // Too little of the body is submerged to overcome its weight.
        assertEquals(0.0, BuoyancyMath.netBuoyantForce(1000.0, 0.25, 500.0, 9.81), EPSILON);
        assertEquals(0.0, BuoyancyMath.netBuoyantForce(1000.0, 1.0, 500.0, 0.0), EPSILON);
        assertEquals(0.0, BuoyancyMath.netBuoyantForce(0.0, 1.0, 500.0, 9.81), EPSILON);
    }

    @Test
    void densityAndWaterDragAreSane() {
        assertEquals(500.0, BuoyancyMath.density(500.0, 1.0), EPSILON);
        assertEquals(Double.POSITIVE_INFINITY, BuoyancyMath.density(500.0, 0.0), EPSILON);
        assertFalse(BuoyancyMath.floats(2400.0, 1.0, 1000.0));
        assertFalse(BuoyancyMath.floats(1000.0, 1.0, 1000.0));
        assertTrue(BuoyancyMath.floats(900.0, 1.0, 1000.0));

        // buoyancyDamping = 0.5, one cubic metre of displaced water.
        assertEquals(500.0, BuoyancyMath.dragCoefficient(0.5, 1000.0), EPSILON);
        assertEquals(0.0, BuoyancyMath.dragCoefficient(0.0, 1000.0), EPSILON);
        assertEquals(0.0, BuoyancyMath.dragCoefficient(-1.0, 1000.0), EPSILON);
    }
}
