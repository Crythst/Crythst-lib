package io.mikaple.crythst.integration.minecraft.entity.riding;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.joml.Vector3d;

/**
 * Read-only snapshot of an entity's attachment to a physics structure.
 *
 * <p>{@link #localPoint()} is in structure-local metres and matches
 * {@link PhysicsStructure#pose()}. An explicit attachment was created through
 * {@link EntityStructureRidingManager#bind}; an automatic one was detected from
 * contact with a structure surface. Allowlisted entities stay bound even when
 * the support probe cannot find a surface.</p>
 */
public record EntityStructureAttachment(
        PhysicsStructure structure,
        long structureBodyId,
        Vector3d localPoint,
        double localYaw,
        boolean explicit,
        boolean allowlisted,
        int ticks
) {
    public EntityStructureAttachment {
        localPoint = new Vector3d(localPoint);
    }

    @Override
    public Vector3d localPoint() {
        return new Vector3d(localPoint);
    }

    /**
     * World position of the attachment point for the structure's current pose.
     * Server thread only.
     */
    public Vector3d worldPoint() {
        return structure.pose().transformPoint(localPoint);
    }
}
