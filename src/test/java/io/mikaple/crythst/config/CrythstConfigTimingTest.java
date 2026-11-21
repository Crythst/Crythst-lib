package io.mikaple.crythst.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrythstConfigTimingTest {
    @Test
    void defaultsUseServerTickTimingAndSixtyHertzSteps() {
        final CrythstConfig config = new CrythstConfig();
        assertTrue(config.serverTickTiming);
        assertEquals(1.0 / 60.0, config.fixedTimeStep, 1.0e-12);
        assertEquals(8, config.maxSubSteps);
    }

    @Test
    void sanitiseRaisesMaxSubStepsForSmallFixedSteps() {
        final CrythstConfig config = new CrythstConfig();
        config.fixedTimeStep = 1.0 / 120.0;
        config.maxSubSteps = 4;
        config.sanitised();
        assertTrue(config.maxSubSteps >= 7,
                "1/120 s needs at least six substeps per 20 TPS tick: " + config.maxSubSteps);
    }

    @Test
    void sanitiseKeepsLargerExplicitCatchUpLimit() {
        final CrythstConfig config = new CrythstConfig();
        config.fixedTimeStep = 1.0 / 60.0;
        config.maxSubSteps = 16;
        config.sanitised();
        assertEquals(16, config.maxSubSteps);
    }
}
