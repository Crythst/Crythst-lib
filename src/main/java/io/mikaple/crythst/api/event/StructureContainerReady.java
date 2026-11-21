package io.mikaple.crythst.api.event;

/**
 * Called once when a level's Crythst structure container becomes available.
 *
 * <p>Register through {@link CrythstEvents#registerStructureContainerReady}.
 * The callback runs on the server thread.</p>
 */
@FunctionalInterface
public interface StructureContainerReady {

    /**
     * @param event level and the ready physics pipeline
     */
    void onStructureContainerReady(StructureContainerReadyEvent event);
}
