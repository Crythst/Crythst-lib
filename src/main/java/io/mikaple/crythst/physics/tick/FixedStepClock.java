package io.mikaple.crythst.physics.tick;

import io.mikaple.crythst.util.Validate;

/**
 * Accumulator that converts variable frame/tick time into a fixed number of
 * physics steps. The remainder becomes the physics interpolation alpha.
 *
 * <p>The clock is deliberately independent of Minecraft. In the integration
 * layer it is normally fed a <em>fixed</em> simulation budget once per server
 * tick (see {@code LevelPhysics}), so an exact number of steps runs each tick
 * even when wall-clock time between server ticks jitters.</p>
 */
public final class FixedStepClock {
    /**
     * Step-count tolerance. Without this, a mathematically exact multiple such
     * as {@code advance(0.3)} with {@code fixedTimeStep = 0.1} can evaluate to
     * {@code 2.9999999999999996} and run only two steps. That single floating
     * point undercount makes a body advance 2 steps on one tick and 4 on the
     * next, which is visible as trembling at 20&nbsp;TPS.
     */
    private static final double STEP_EPSILON = 1.0e-9;

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
        final double rawSteps = accumulator / fixedTimeStep;
        final int steps = Math.min((int) Math.floor(rawSteps + STEP_EPSILON), maxSubSteps);
        accumulator -= steps * fixedTimeStep;
        if (accumulator < 0.0 && accumulator > -STEP_EPSILON * fixedTimeStep) {
            // Rounding can leave a tiny negative remainder after the epsilon
            // assisted floor. Treat it as zero so alpha() stays non-negative.
            accumulator = 0.0;
        }

        // Never let interpolation alpha reach 1.0: discard excess catch-up
        // time instead of simulating an unbounded number of steps.
        if (accumulator >= fixedTimeStep) {
            accumulator = fixedTimeStep * 0.999;
        }
        return steps;
    }

    public double alpha() {
        return Math.clamp(accumulator / fixedTimeStep, 0.0, 0.999999);
    }

    public double accumulator() {
        return accumulator;
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
