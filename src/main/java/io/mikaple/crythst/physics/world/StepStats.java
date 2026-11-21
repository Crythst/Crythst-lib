package io.mikaple.crythst.physics.world;

/**
 * Statistics from the most recent fixed step.
 */
public record StepStats(
        int bodyCount,
        int constraintCount,
        int awakeBodyCount,
        int contactCount,
        int subSteps,
        double simulatedSeconds,
        double stepMillis
) {
    public static final StepStats EMPTY = new StepStats(0, 0, 0, 0, 0, 0.0, 0.0);
}
