package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.physics.structure.PhysicsStructure;

/**
 * Observes structure lifecycle changes in a {@link ServerStructureContainer}.
 *
 * <p>Callbacks run on the server thread. They are produced by polling the
 * level's authoritative structure list once per server tick, and immediately
 * after calls made through the container's own create/remove/refresh methods.
 * Register through {@link ServerStructureContainer#addObserver} for one
 * container or {@link StructureContainerEvents#registerObserver} for every
 * container.</p>
 */
public interface StructureObserver {

    /**
     * Called when a structure appears in the container.
     */
    void onStructureAdded(PhysicsStructure structure);

    /**
     * Called when a structure is no longer present in the container.
     */
    void onStructureRemoved(PhysicsStructure structure);

    /**
     * Called after an explicit refresh through
     * {@link ServerStructureContainer#refreshStructure(PhysicsStructure)} when
     * the refreshed structure remained a single body.
     */
    void onStructureRefreshed(PhysicsStructure structure);
}
