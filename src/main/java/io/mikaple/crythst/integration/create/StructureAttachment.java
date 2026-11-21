package io.mikaple.crythst.integration.create;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable attachment of one entity to one {@link PhysicsStructure}.
 *
 * <p>The local transform is stored in {@link net.minecraft.world.entity.Entity#position()}
 * space and is composed with the structure pose every tick. The entity id is
 * kept for persistence and for the riding manager's bookkeeping; the record
 * intentionally holds no entity reference so a stale attachment cannot keep an
 * unloaded entity alive.</p>
 */
public record StructureAttachment(
        UUID entityId,
        PhysicsStructure structure,
        Transform3d localTransform
) implements StructureAttachmentTarget {
    public StructureAttachment {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(localTransform, "localTransform");
    }

    @Override
    public boolean isValid() {
        return !structure.closed();
    }

    /**
     * Returns a copy anchored at a new local pose.
     */
    public StructureAttachment withLocalTransform(Transform3d newLocalTransform) {
        return new StructureAttachment(entityId, structure, newLocalTransform);
    }
}
