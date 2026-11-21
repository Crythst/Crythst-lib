package io.mikaple.crythst.api.event;

/**
 * Called immediately before one fixed physics substep is integrated.
 *
 * <p>Use this instead of a server-tick callback when applying forces or
 * impulses that must scale with simulation time. Register through
 * {@link CrythstEvents#registerPrePhysicsTick(PrePhysicsTick)}.</p>
 */
@FunctionalInterface
public interface PrePhysicsTick {

    /**
     * @param event level, pipeline, fixed time step and substep index
     */
    void onPrePhysicsTick(PhysicsTickEvent event);
}
