package io.mikaple.crythst.api.block;

/**
 * Notifies a block when a cell it occupies changes inside a live structure.
 *
 * <p>Implement this on a {@code Block} class or register it through
 * {@link StructureBlockHooks}. Changes are detected by comparing a block-state
 * snapshot taken at the previous collider rebuild with the current volume.
 * Volumes that cannot expose block states (for example a bare
 * {@code MutableVoxelVolume}) never produce these events.</p>
 *
 * <p>Listeners run on the thread that calls {@code PhysicsStructure#reindex},
 * normally the server thread. Do not mutate the volume from the listener.
 * Register before a structure is assembled for change tracking from its first
 * edit; registering later seeds the snapshot on the next reindex, so only
 * changes after that reindex are reported.</p>
 */
@FunctionalInterface
public interface BlockStructureBlockChangeListener {

    /**
     * Called once for each changed cell whose old or new block registered this
     * listener.
     */
    void onStructureBlockChanged(StructureBlockChange change);
}
