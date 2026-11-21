package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Conversions between the three coordinate spaces used by constraints.
 *
 * <p>A {@link PhysicsStructure structure} pose maps structure-local metres to
 * world space: {@code world = structure.pose() * local}. The underlying rigid
 * body origin is the structure's centre of mass, so the body pose differs from
 * the structure pose by a pure translation. Backend constraint definitions
 * expect pivots and frame positions in body-local space.</p>
 *
 * <p><strong>Units:</strong> positions and frame translations are metres;
 * directions are unitless and are normalized by the backend where required.</p>
 *
 * <p><strong>Threading:</strong> server thread only; every method reads the
 * live structure and body poses.</p>
 */
public final class ConstraintFrames {
    private ConstraintFrames() {
    }

    /**
     * Converts a structure-local frame to world space.
     *
     * @param structure structure whose pose defines the local frame
     * @param local     frame in structure-local metres
     * @return the same frame in world space
     */
    public static Transform3d structureToWorld(PhysicsStructure structure, Transform3d local) {
        Validate.notNull(structure, "structure");
        Validate.notNull(local, "local");
        return structure.pose().compose(local);
    }

    /**
     * Converts a world-space frame to structure-local space.
     *
     * @param structure structure whose pose defines the local frame
     * @param world     frame in world space
     * @return the same frame in structure-local metres
     */
    public static Transform3d worldToStructure(PhysicsStructure structure, Transform3d world) {
        Validate.notNull(structure, "structure");
        Validate.notNull(world, "world");
        return structure.pose().inverse().compose(world);
    }

    /**
     * Converts a structure-local frame to the body-local frame expected by
     * constraint definitions.
     *
     * <p>This accounts for the structure pose and for the body origin being the
     * structure's centre of mass: for a frame {@code F},
     * {@code result = bodyPose^-1 * structurePose * F}.</p>
     *
     * @param structure structure owning the body
     * @param local     frame in structure-local metres
     * @return the same frame in body-local metres
     */
    public static Transform3d structureToBody(PhysicsStructure structure, Transform3d local) {
        Validate.notNull(structure, "structure");
        Validate.notNull(local, "local");
        return structure.body().transform().inverse().compose(structure.pose().compose(local));
    }

    /**
     * Converts a body-local frame back to structure-local space.
     *
     * @param structure structure owning the body
     * @param body      frame in body-local metres
     * @return the same frame in structure-local metres
     */
    public static Transform3d bodyToStructure(PhysicsStructure structure, Transform3d body) {
        Validate.notNull(structure, "structure");
        Validate.notNull(body, "body");
        return structure.pose().inverse().compose(structure.body().transform().compose(body));
    }

    /**
     * Converts a structure-local point to world space.
     */
    public static Vector3d structurePointToWorld(PhysicsStructure structure, Vector3dc local) {
        Validate.notNull(structure, "structure");
        Validate.notNull(local, "local");
        return structure.pose().transformPoint(local);
    }

    /**
     * Converts a world-space point to structure-local space.
     */
    public static Vector3d worldPointToStructure(PhysicsStructure structure, Vector3dc world) {
        Validate.notNull(structure, "structure");
        Validate.notNull(world, "world");
        return structure.pose().inverseTransformPoint(world);
    }

    /**
     * Converts a structure-local point to the body-local space expected by
     * constraint definitions.
     */
    public static Vector3d structurePointToBody(PhysicsStructure structure, Vector3dc local) {
        Validate.notNull(structure, "structure");
        Validate.notNull(local, "local");
        return structure.body().transform().inverseTransformPoint(
                structure.pose().transformPoint(local));
    }

    /**
     * Converts a body-local point to structure-local space.
     */
    public static Vector3d bodyPointToStructure(PhysicsStructure structure, Vector3dc body) {
        Validate.notNull(structure, "structure");
        Validate.notNull(body, "body");
        return structure.pose().inverseTransformPoint(
                structure.body().transform().transformPoint(body));
    }

    /**
     * Converts a structure-local direction to world space.
     */
    public static Vector3d structureDirectionToWorld(PhysicsStructure structure, Vector3dc local) {
        Validate.notNull(structure, "structure");
        Validate.notNull(local, "local");
        return structure.pose().transformDirection(local);
    }

    /**
     * Converts a world-space direction to structure-local space.
     */
    public static Vector3d worldDirectionToStructure(PhysicsStructure structure, Vector3dc world) {
        Validate.notNull(structure, "structure");
        Validate.notNull(world, "world");
        return structure.pose().inverseTransformDirection(world);
    }

    /**
     * Converts a structure-local direction to the body-local direction expected
     * by constraint definitions.
     */
    public static Vector3d structureDirectionToBody(PhysicsStructure structure, Vector3dc local) {
        Validate.notNull(structure, "structure");
        Validate.notNull(local, "local");
        return structure.body().transform().inverseTransformDirection(
                structure.pose().transformDirection(local));
    }

    /**
     * Converts a body-local direction to structure-local space.
     */
    public static Vector3d bodyDirectionToStructure(PhysicsStructure structure, Vector3dc body) {
        Validate.notNull(structure, "structure");
        Validate.notNull(body, "body");
        return structure.pose().inverseTransformDirection(
                structure.body().transform().transformDirection(body));
    }
}
