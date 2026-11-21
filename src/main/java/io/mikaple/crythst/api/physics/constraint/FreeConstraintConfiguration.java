package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.PointConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.util.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Ball/point joint: the two anchor points are held together while all relative
 * rotation remains free.
 *
 * <p>This maps to {@code PointConstraintDef}. {@code anchorA} is
 * structure-local to structure A; {@code anchorB} is structure-local to
 * structure B or world-space for a world-anchored joint.</p>
 *
 * <p>See {@link StructureConstraintConfiguration} for frame conventions and
 * units.</p>
 */
public record FreeConstraintConfiguration(
        Vector3d anchorA,
        Vector3d anchorB
) implements StructureConstraintConfiguration {

    public FreeConstraintConfiguration {
        anchorA = new Vector3d(Validate.notNull(anchorA, "anchorA"));
        anchorB = new Vector3d(Validate.notNull(anchorB, "anchorB"));
    }

    /**
     * Creates a point joint between two structures.
     *
     * @param anchorA attachment point in structure A local metres
     * @param anchorB attachment point in structure B local metres
     */
    public static FreeConstraintConfiguration between(Vector3dc anchorA, Vector3dc anchorB) {
        return new FreeConstraintConfiguration(new Vector3d(anchorA), new Vector3d(anchorB));
    }

    /**
     * Creates a point joint from structure A to a world-space anchor.
     *
     * @param worldAnchor attachment point in world metres
     */
    public static FreeConstraintConfiguration toWorld(Vector3dc worldAnchor) {
        return new FreeConstraintConfiguration(new Vector3d(), new Vector3d(worldAnchor));
    }

    @Override
    public Vector3d anchorA() {
        return new Vector3d(anchorA);
    }

    @Override
    public Vector3d anchorB() {
        return new Vector3d(anchorB);
    }

    /** Returns a copy with a different structure-A-local anchor. */
    public FreeConstraintConfiguration withAnchorA(Vector3dc newAnchorA) {
        return new FreeConstraintConfiguration(new Vector3d(newAnchorA), anchorB);
    }

    /**
     * Returns a copy with a different structure-B-local anchor, or a different
     * world anchor for a world-anchored joint.
     */
    public FreeConstraintConfiguration withAnchorB(Vector3dc newAnchorB) {
        return new FreeConstraintConfiguration(anchorA, new Vector3d(newAnchorB));
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.POINT;
    }

    @Override
    public ConstraintDef toDefinition(PhysicsStructure structureA,
                                      @Nullable PhysicsStructure structureB) {
        final Vector3d pivotA = ConstraintFrames.structurePointToBody(structureA, anchorA);
        if (structureB == null) {
            return new PointConstraintDef(structureA.body(), null, pivotA, new Vector3d(anchorB));
        }
        return new PointConstraintDef(structureA.body(), structureB.body(), pivotA,
                ConstraintFrames.structurePointToBody(structureB, anchorB));
    }
}
