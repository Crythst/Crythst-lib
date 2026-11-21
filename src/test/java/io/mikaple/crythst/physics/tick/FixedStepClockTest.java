package io.mikaple.crythst.physics.tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedStepClockTest {
    @Test
    void accumulatesPartialSteps() {
        final FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 4);
        assertEquals(0, clock.advance(1.0 / 120.0));
        assertTrue(clock.alpha() > 0.49 && clock.alpha() < 0.51);
        assertEquals(1, clock.advance(1.0 / 120.0));
        assertEquals(0.0, clock.alpha(), 1.0e-9);
    }

    @Test
    void clampsCatchUpToMaxSubSteps() {
        final FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 4);
        assertEquals(4, clock.advance(1.0));
        assertTrue(clock.alpha() <= 1.0);
    }

    @Test
    void ignoresNonPositiveTime() {
        final FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 4);
        assertEquals(0, clock.advance(0.0));
        assertEquals(0, clock.advance(-1.0));
    }
}
