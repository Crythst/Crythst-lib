package io.mikaple.crythst.physics.world;

/**
 * Receives callbacks around every fixed physics step.
 */
public interface StepListener {
    default void beforeStep(PhysicsWorld world, double fixedTimeStep) {
    }

    default void afterStep(PhysicsWorld world, double fixedTimeStep, StepStats stats) {
    }
}
