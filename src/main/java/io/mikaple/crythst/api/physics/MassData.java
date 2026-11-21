package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.math.CrythstMath;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Immutable mass properties of a body.
 *
 * <p>The inertia tensor is expressed in the body's local frame about its centre
 * of mass. Values use SI units: kilograms for mass and {@code kg·m²} for the
 * tensor. The centre of mass is local to the structure origin in metres; for a
 * loose body it is the body-local origin.</p>
 */
public final class MassData {
    private static final Matrix3d ZERO = new Matrix3d().zero();

    private final double mass;
    private final double inverseMass;
    private final Matrix3d inertia;
    private final Matrix3d inverseInertia;
    private final Vector3d centerOfMass;

    /**
     * @param mass          total mass in kilograms, non-negative
     * @param inertia       local inertia tensor in {@code kg·m²}
     * @param centerOfMass  local centre of mass in metres
     */
    public MassData(double mass, Matrix3dc inertia, Vector3dc centerOfMass) {
        if (!Double.isFinite(mass) || mass < 0.0) {
            throw new IllegalArgumentException("mass must be finite and non-negative: " + mass);
        }
        this.mass = mass;
        this.inverseMass = mass > 0.0 ? 1.0 / mass : 0.0;
        this.inertia = inertia == null ? new Matrix3d(ZERO) : new Matrix3d(inertia);
        final Matrix3d inverted = CrythstMath.invertInertia(this.inertia);
        this.inverseInertia = inverted == null ? new Matrix3d(ZERO) : inverted;
        this.centerOfMass = centerOfMass == null ? new Vector3d() : new Vector3d(centerOfMass);
    }

    /**
     * Builds mass data from a structure collider.
     */
    public static MassData forStructure(PhysicsStructure structure) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        final VoxelColliderData collider = structure.collider();
        final double mass = collider.mass() * structure.massScale();
        final Matrix3d inertia = new Matrix3d(collider.inertia()).scale(structure.massScale());
        final Vector3d center = new Vector3d(collider.centerOfMass()).mul(structure.blockScale());
        return new MassData(mass, inertia, center);
    }

    public double mass() {
        return mass;
    }

    public double inverseMass() {
        return inverseMass;
    }

    /**
     * @return a copy of the local inertia tensor; modifying it does not affect
     *         the body
     */
    public Matrix3d inertia() {
        return new Matrix3d(inertia);
    }

    /**
     * @return a copy of the local inverse inertia tensor, or a zero matrix when
     *         the tensor is singular
     */
    public Matrix3d inverseInertia() {
        return new Matrix3d(inverseInertia);
    }

    /**
     * @return a copy of the local centre of mass in metres
     */
    public Vector3d centerOfMass() {
        return new Vector3d(centerOfMass);
    }

    public boolean isValid() {
        return mass > 0.0;
    }
}
