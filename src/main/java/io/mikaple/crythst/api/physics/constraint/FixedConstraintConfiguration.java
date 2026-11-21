package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.FixedConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;

/**
 * Welds two structures, or one structure to the world, at a frame.
 *
 * <p>{@code frameA} is structure-local to structure A. {@code frameB} is
 * structure-local to structure B, or world-space for a world-anchored joint.
 * Both the position and rotation of the frames are locked, so the two frames
 * coincide after the joint is created.</p>
 *
 * <p>See {@link StructureConstraintConfiguration} for frame conventions and
 * units.</p>
 */
public record FixedConstraintConfiguration(
        Transform3d frameA,
        Transform3d frameB
) implements StructureConstraintConfiguration {

    public FixedConstraintConfiguration {
        Validate.notNull(frameA, "frameA");
        Validate.notNull(frameB, "frameB");
    }

    /**
     * Creates a frame-to-frame weld between two structures.
     *
     * @param frameA attachment frame in structure A local metres
     * @param frameB attachment frame in structure B local metres
     */
    public static FixedConstraintConfiguration between(Transform3d frameA, Transform3d frameB) {
        return new FixedConstraintConfiguration(frameA, frameB);
    }

    /**
     * Creates a weld from structure A to the world.
     *
     * @param worldFrame attachment frame in world-space metres
     */
    public static FixedConstraintConfiguration toWorld(Transform3d worldFrame) {
        return new FixedConstraintConfiguration(Transform3d.IDENTITY, worldFrame);
    }

    /** Returns a copy with a different structure-A-local frame. */
    public FixedConstraintConfiguration withFrameA(Transform3d newFrameA) {
        return new FixedConstraintConfiguration(newFrameA, frameB);
    }

    /**
     * Returns a copy with a different structure-B-local frame, or a different
     * world frame for a world-anchored joint.
     */
    public FixedConstraintConfiguration withFrameB(Transform3d newFrameB) {
        return new FixedConstraintConfiguration(frameA, newFrameB);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.FIXED;
    }

    @Override
    public ConstraintDef toDefinition(PhysicsStructure structureA,
                                      @Nullable PhysicsStructure structureB) {
        final Transform3d bodyFrameA = ConstraintFrames.structureToBody(structureA, frameA);
        if (structureB == null) {
            // The backend reads frame B as a world frame when body B is absent.
            return new FixedConstraintDef(structureA.body(), null, bodyFrameA, frameB);
        }
        return new FixedConstraintDef(structureA.body(), structureB.body(),
                bodyFrameA, ConstraintFrames.structureToBody(structureB, frameB));
    }
}
