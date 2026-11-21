package io.mikaple.crythst.api.block;

import io.mikaple.crythst.physics.structure.PhysicsStructure;

/**
 * Notifies a block that its cells are being copied into a physics structure.
 *
 * <p>Implement this on a {@code Block} class or register it through
 * {@link StructureBlockHooks}. The listener is invoked once per structure, not
 * once per block, when at least one solid cell uses that block. Assembly runs
 * on the server thread through {@code LevelPhysics#createStructure}; automatic
 * splits and restores go through the same path.</p>
 */
public interface BlockStructureAssemblyListener {

    /**
     * Called before the body and its collider are created.
     *
     * @param context level and source volume
     */
    default void beforeStructureAssembly(StructureAssemblyContext context) {
    }

    /**
     * Called after the body exists and is part of its level's physics world.
     *
     * @param context   level and source volume
     * @param structure the newly created structure
     */
    default void afterStructureAssembly(StructureAssemblyContext context, PhysicsStructure structure) {
    }
}
