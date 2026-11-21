package io.mikaple.crythst.api.block;

/**
 * Per-physics-substep callback for a block entity that is part of a Crythst
 * moving structure.
 *
 * <p>A block entity whose class implements this interface is discovered
 * automatically when its structure is copied into the auxiliary ticking level.
 * It is then invoked once per fixed physics substep, immediately before that
 * substep is integrated. There may be zero, one or several substeps per server
 * tick, so implementations must use the supplied time step rather than assuming
 * {@code 1/20 s}.</p>
 *
 * <p><strong>Side:</strong> server thread only. Crythst never invokes actors
 * from the render thread or on a client level. <strong>Lifecycle:</strong> the
 * actor list is cached per structure and rebuilt when the structure is
 * reindexed, refreshed, or removed. If a callback throws, Crythst logs it once
 * per actor/structure pair and continues with the remaining actors; an
 * exception does not disassemble the structure.</p>
 *
 * <p>Do not retain the {@link StructurePhysicsContext} or any
 * {@code QueuedForceGroup} obtained from it after the callback returns.</p>
 *
 * @see StructurePhysicsContext
 */
@FunctionalInterface
public interface BlockEntityStructureActor {

    /**
     * Called once per fixed physics substep before integration.
     *
     * <p>Use {@link StructurePhysicsContext#forceGroup} to queue forces in
     * newtons; the dispatcher converts them to impulses with the substep length
     * after all actors have run. Direct velocity changes through
     * {@link StructurePhysicsContext#body()} are also allowed and take effect
     * in the same substep.</p>
     *
     * @param context ephemeral view of the structure being simulated
     */
    void structurePhysicsTick(StructurePhysicsContext context);
}
