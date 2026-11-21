package io.mikaple.crythst.api.event;

/**
 * Called immediately after one fixed physics substep has been integrated.
 *
 * <p>Velocities and poses observed here belong to the step identified by
 * {@link PhysicsTickEvent#tickIndex()}. Register through
 * {@link CrythstEvents#registerPostPhysicsTick(PostPhysicsTick)}.</p>
 */
@FunctionalInterface
public interface PostPhysicsTick {

    /**
     * @param event level, pipeline, fixed time step and substep index
     */
    void onPostPhysicsTick(PhysicsTickEvent event);
}
