package io.mikaple.crythst.integration.create;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.joml.Vector3d;

/**
 * Everything the entity riding manager needs to know to follow one physics
 * structure: which structure, and where in that structure's local space the
 * entity anchor lives.
 *
 * <p>The interface deliberately does not mention Create. The riding manager can
 * implement it for any handle (a contraption entity, a passenger, a seat) and
 * hand it to {@link CreateStructureBridge#apply}. {@link StructureAttachment} is
 * the ready-made implementation used by the bridge's registry, but a caller with
 * its own lifecycle can skip that registry entirely.</p>
 */
public interface StructureAttachmentTarget {
    /**
     * Structure the entity is glued to. Must not be {@code null}.
     */
    PhysicsStructure structure();

    /**
     * Entity anchor expressed in structure-local metres (the coordinate space
     * of {@link PhysicsStructure#pose()}). This is the entity's
     * {@code position()} space, not Create's {@code getAnchorVec()} space.
     */
    Transform3d localTransform();

    /**
     * Body id of the attached structure. Stable for the lifetime of a
     * structure and useful for persistence and diagnostics.
     */
    default long structureId() {
        return structure().body().id();
    }

    /**
     * Current world-space entity anchor: {@code structurePose * localTransform}.
     */
    default Transform3d worldTransform() {
        return structure().pose().compose(localTransform());
    }

    /**
     * Linear velocity of the structure at the anchor point. The riding manager
     * can use this to inherit platform motion instead of fighting gravity.
     */
    default Vector3d worldVelocity() {
        return structure().velocityAt(worldTransform().position());
    }

    /**
     * Whether the attachment can still be applied. The default rejects a
     * closed structure.
     */
    default boolean isValid() {
        return !structure().closed();
    }

    /**
     * Notification invoked after {@link CreateStructureBridge#apply} has
     * written the world pose. Override to sync a client packet, update a rider
     * offset, or release the attachment.
     *
     * @param worldTransform pose that was just applied
     */
    default void onPoseApplied(Transform3d worldTransform) {
    }
}
