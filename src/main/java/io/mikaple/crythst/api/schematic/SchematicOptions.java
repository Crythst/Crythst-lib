package io.mikaple.crythst.api.schematic;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.util.Validate;

import java.util.Objects;

/**
 * Placement and rigid-body options used to turn a {@link Schematic} into a
 * {@link StructureDef}.
 *
 * <p>The pose maps structure-local metres to world space, exactly like
 * {@link StructureDef#pose()}. Voxel {@code (x,y,z)} is therefore centred at
 * {@code pose * ((x + 0.5) * blockScale, ...)} in world space. {@code blockScale}
 * is metres per voxel and must be positive.</p>
 *
 * @param pose                 structure pose; identity keeps voxel zero at the
 *                             world origin
 * @param blockScale           metres per voxel
 * @param type                 rigid-body type
 * @param material             default surface material
 * @param massScale            multiplier applied to collider mass
 * @param linearDamping        linear damping
 * @param angularDamping       angular damping
 * @param ccd                  continuous collision detection
 * @param attachBlockEntities  reattach decoded block-entity NBT to the
 *                             auxiliary ticking level when instantiating
 */
public record SchematicOptions(
        Transform3d pose,
        double blockScale,
        BodyType type,
        PhysicsMaterial material,
        double massScale,
        double linearDamping,
        double angularDamping,
        boolean ccd,
        boolean attachBlockEntities
) {
    /** One metre per voxel, identity pose, dynamic body. */
    public static final SchematicOptions DEFAULTS = new SchematicOptions(
            Transform3d.IDENTITY, 1.0, BodyType.DYNAMIC, PhysicsMaterial.DEFAULT,
            1.0, 0.0, 0.0, false, true);

    public SchematicOptions {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(material, "material");
        blockScale = Validate.positive(blockScale, "blockScale");
        massScale = Validate.positive(massScale, "massScale");
        linearDamping = Validate.nonNegative(linearDamping, "linearDamping");
        angularDamping = Validate.nonNegative(angularDamping, "angularDamping");
    }

    public static SchematicOptions defaults() {
        return DEFAULTS;
    }

    public SchematicOptions withPose(Transform3d newPose) {
        return new SchematicOptions(newPose, blockScale, type, material, massScale,
                linearDamping, angularDamping, ccd, attachBlockEntities);
    }

    public SchematicOptions withBlockScale(double newBlockScale) {
        return new SchematicOptions(pose, newBlockScale, type, material, massScale,
                linearDamping, angularDamping, ccd, attachBlockEntities);
    }

    public SchematicOptions withType(BodyType newType) {
        return new SchematicOptions(pose, blockScale, newType, material, massScale,
                linearDamping, angularDamping, ccd, attachBlockEntities);
    }

    public SchematicOptions withMaterial(PhysicsMaterial newMaterial) {
        return new SchematicOptions(pose, blockScale, type, newMaterial, massScale,
                linearDamping, angularDamping, ccd, attachBlockEntities);
    }

    public SchematicOptions withMassScale(double newMassScale) {
        return new SchematicOptions(pose, blockScale, type, material, newMassScale,
                linearDamping, angularDamping, ccd, attachBlockEntities);
    }

    public SchematicOptions withDamping(double newLinearDamping, double newAngularDamping) {
        return new SchematicOptions(pose, blockScale, type, material, massScale,
                newLinearDamping, newAngularDamping, ccd, attachBlockEntities);
    }

    public SchematicOptions withCcd(boolean enabled) {
        return new SchematicOptions(pose, blockScale, type, material, massScale,
                linearDamping, angularDamping, enabled, attachBlockEntities);
    }

    public SchematicOptions withBlockEntities(boolean attach) {
        return new SchematicOptions(pose, blockScale, type, material, massScale,
                linearDamping, angularDamping, ccd, attach);
    }

    /**
     * Applies these options to a definition builder.
     *
     * @param builder builder that already owns the schematic volume
     * @return the same builder, for chaining
     */
    public StructureDef.Builder applyTo(StructureDef.Builder builder) {
        return builder
                .pose(pose)
                .type(type)
                .material(material)
                .colliderOptions(VoxelColliderOptions.DEFAULT.withBlockScale(blockScale))
                .massScale(massScale)
                .damping(linearDamping, angularDamping)
                .ccd(ccd);
    }
}
