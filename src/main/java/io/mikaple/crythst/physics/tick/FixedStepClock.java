package io.mikaple.crythst.physics.tick;

import io.mikaple.crythst.util.Validate;

/**
 * Accumulator that converts variable frame time into a fixed number of physics
 * steps. The remainder becomes the render interpolation alpha.
 */
public final class FixedStepClock {
    private final double fixedTimeStep;
    private final int maxSubSteps;
    private double accumulator;

    public FixedStepClock(double fixedTimeStep, int maxSubSteps) {
        this.fixedTimeStep = Validate.positive(fixedTimeStep, "fixedTimeStep");
        this.maxSubSteps = Validate.atLeast(maxSubSteps, 1, "maxSubSteps");
    }

    /**
     * Adds elapsed time and returns how many fixed steps should run. Excess
     * time beyond {@code maxSubSteps} is discarded to avoid a spiral of death.
     */
    public int advance(double realSeconds) {
        Validate.finite(realSeconds, "realSeconds");
        if (realSeconds <= 0.0) {
            return 0;
        }

        accumulator += realSeconds;
        final int steps = Math.min((int) (accumulator / fixedTimeStep), maxSubSteps);
        accumulator -= steps * fixedTimeStep;

        // Never let interpolation alpha reach 1.0: discard excess catch-up
        // time instead of simulating an unbounded number of steps.
        if (accumulator >= fixedTimeStep) {
            accumulator = fixedTimeStep * 0.999;
        }
        return steps;
    }

    public double alpha() {
        return accumulator / fixedTimeStep;
    }

    public double fixedTimeStep() {
        return fixedTimeStep;
    }

    public int maxSubSteps() {
        return maxSubSteps;
    }

    public void reset() {
        accumulator = 0.0;
    }
}
