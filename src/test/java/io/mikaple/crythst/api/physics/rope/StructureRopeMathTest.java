package io.mikaple.crythst.api.physics.rope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureRopeMathTest {
    @Test
    void slackRopeProducesNoTension() {
        assertEquals(0.0, StructureRope.computeTension(2.0, 3.0, 100.0, 10.0, 0.0, 1000.0), 0.0);
    }

    @Test
    void tensionCombinesSpringAndSeparationDamping() {
        assertEquals(110.0,
                StructureRope.computeTension(3.0, 2.0, 100.0, 10.0, 1.0, 1000.0), 1.0e-9);
    }

    @Test
    void tensionIsClampedAndNonFiniteInputsAreSafe() {
        assertEquals(1000.0,
                StructureRope.computeTension(100.0, 0.0, 100.0, 0.0, 0.0, 1000.0), 1.0e-9);
        assertEquals(0.0,
                StructureRope.computeTension(Double.NaN, 1.0, 100.0, 0.0, 0.0, 1000.0), 0.0);
        assertEquals(0.0,
                StructureRope.computeTension(2.0, 1.0, 100.0, 0.0, 0.0, -1.0), 0.0);
    }
}
