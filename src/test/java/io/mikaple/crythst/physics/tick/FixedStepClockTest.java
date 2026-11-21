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

    @Test
    void serverTickBudgetGivesStableStepCountAt20Tps() {
        final FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 8);
        for (int tick = 0; tick < 400; tick++) {
            assertEquals(3, clock.advance(1.0 / 20.0),
                    "tick " + tick + " must run exactly three 1/60 s steps");
            assertEquals(0.0, clock.alpha(), 1.0e-9);
        }
    }

    @Test
    void serverTickBudgetGivesStableStepCountAt120HzPhysics() {
        final FixedStepClock clock = new FixedStepClock(1.0 / 120.0, 8);
        for (int tick = 0; tick < 400; tick++) {
            assertEquals(6, clock.advance(1.0 / 20.0),
                    "tick " + tick + " must run exactly six 1/120 s steps");
            assertEquals(0.0, clock.alpha(), 1.0e-9);
        }
    }

    @Test
    void floatingPointBoundaryDoesNotDropAStep() {
        // 0.3 / 0.1 evaluates to 2.9999999999999996 in double arithmetic.
        final FixedStepClock clock = new FixedStepClock(0.1, 8);
        assertEquals(3, clock.advance(0.3));
        assertEquals(0.0, clock.alpha(), 1.0e-9);
    }

    @Test
    void alphaStaysInRangeWhenCatchUpIsDiscarded() {
        final FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 3);
        assertEquals(3, clock.advance(1.0));
        assertTrue(clock.alpha() >= 0.0 && clock.alpha() < 1.0);
        assertEquals(3, clock.advance(1.0 / 20.0));
        assertTrue(clock.alpha() >= 0.0 && clock.alpha() < 1.0);
    }
}
