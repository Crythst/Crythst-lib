package io.mikaple.crythst.api.physics.box;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Immutable description of a loose axis-aligned box body.
 *
 * <p>Half extents are metres; the box's local origin is its centre. A
 * {@code DYNAMIC} box with {@code mass == 0} derives its mass from
 * {@code volume * material.density}.</p>
 */
public record StructureBoxDef(
        Vector3d halfExtents,
        Transform3d pose,
        BodyType type,
        double mass,
        PhysicsMaterial material,
        CollisionFilter filter,
        double linearDamping,
        double angularDamping,
        boolean ccd,
        @Nullable Object userData
) {
    public StructureBoxDef {
        if (halfExtents == null) {
            throw new NullPointerException("halfExtents");
        }
        halfExtents = new Vector3d(halfExtents);
        if (!(halfExtents.x > 0.0) || !(halfExtents.y > 0.0) || !(halfExtents.z > 0.0)
                || !Double.isFinite(halfExtents.x) || !Double.isFinite(halfExtents.y)
                || !Double.isFinite(halfExtents.z)) {
            throw new IllegalArgumentException(
                    "halfExtents must be finite and positive: " + halfExtents);
        }
        pose = pose == null ? Transform3d.IDENTITY : pose;
        type = type == null ? BodyType.DYNAMIC : type;
        if (!Double.isFinite(mass) || mass < 0.0) {
            throw new IllegalArgumentException("mass must be finite and non-negative: " + mass);
        }
        material = material == null ? PhysicsMaterial.DEFAULT : material;
        filter = filter == null ? CollisionFilter.DEFAULT : filter;
        if (!Double.isFinite(linearDamping) || linearDamping < 0.0
                || !Double.isFinite(angularDamping) || angularDamping < 0.0) {
            throw new IllegalArgumentException("damping must be finite and non-negative");
        }
    }

    /** Dynamic box with the given half extents and centre position. */
    public static StructureBoxDef dynamic(Vector3dc halfExtents, Vector3dc position) {
        return new StructureBoxDef(new Vector3d(halfExtents),
                Transform3d.of(position), BodyType.DYNAMIC, 0.0,
                PhysicsMaterial.DEFAULT, CollisionFilter.DEFAULT, 0.0, 0.0, false, null);
    }

    /** Static box with the given half extents and centre position. */
    public static StructureBoxDef staticBox(Vector3dc halfExtents, Vector3dc position) {
        return new StructureBoxDef(new Vector3d(halfExtents),
                Transform3d.of(position), BodyType.STATIC, 0.0,
                PhysicsMaterial.DEFAULT, CollisionFilter.DEFAULT, 0.0, 0.0, false, null);
    }

    /** Kinematic box with the given half extents and centre position. */
    public static StructureBoxDef kinematic(Vector3dc halfExtents, Vector3dc position) {
        return new StructureBoxDef(new Vector3d(halfExtents),
                Transform3d.of(position), BodyType.KINEMATIC, 0.0,
                PhysicsMaterial.DEFAULT, CollisionFilter.DEFAULT, 0.0, 0.0, false, null);
    }

    /** Returns a copy with a different pose. */
    public StructureBoxDef withPose(Transform3d newPose) {
        return new StructureBoxDef(halfExtents, newPose, type, mass, material, filter,
                linearDamping, angularDamping, ccd, userData);
    }

    /** Returns a copy with a different body type. */
    public StructureBoxDef withType(BodyType newType) {
        return new StructureBoxDef(halfExtents, pose, newType, mass, material, filter,
                linearDamping, angularDamping, ccd, userData);
    }

    /** Returns a copy with an explicit mass in kilograms. */
    public StructureBoxDef withMass(double newMass) {
        return new StructureBoxDef(halfExtents, pose, type, newMass, material, filter,
                linearDamping, angularDamping, ccd, userData);
    }

    /** Returns a copy with a material. */
    public StructureBoxDef withMaterial(PhysicsMaterial newMaterial) {
        return new StructureBoxDef(halfExtents, pose, type, mass, newMaterial, filter,
                linearDamping, angularDamping, ccd, userData);
    }

    /** Returns a copy with damping coefficients. */
    public StructureBoxDef withDamping(double linear, double angular) {
        return new StructureBoxDef(halfExtents, pose, type, mass, material, filter,
                linear, angular, ccd, userData);
    }

    /** Returns a copy with a collision filter. */
    public StructureBoxDef withFilter(CollisionFilter newFilter) {
        return new StructureBoxDef(halfExtents, pose, type, mass, material, newFilter,
                linearDamping, angularDamping, ccd, userData);
    }

    /** Returns a copy with CCD enabled or disabled. */
    public StructureBoxDef withCcd(boolean enabled) {
        return new StructureBoxDef(halfExtents, pose, type, mass, material, filter,
                linearDamping, angularDamping, enabled, userData);
    }
}
