package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.util.Validate;

import java.util.Objects;

/**
 * Options for {@link StructureAssembly#assemble}.
 *
 * <p>{@link #pose()} is applied relative to the supplied anchor block corner.
 * An identity pose keeps voxel zero at the anchor; a rotation rotates the
 * captured block group around the anchor. A non-zero pose position is an extra
 * world-space offset. The final structure pose is therefore
 * {@code anchor + posePosition + rotation * (capturedMin - anchor)}.</p>
 *
 * @param pose               extra pose relative to the anchor
 * @param type               rigid-body type
 * @param material           default surface material
 * @param massScale          multiplier applied to the per-block masses
 * @param blockScale         metres per voxel
 * @param removeFromWorld    remove captured blocks after the body is created
 * @param copyBlockEntities  capture and reattach block-entity NBT
 * @param keepEntities       leave entities in the world; Crythst structures do
 *                           not own an entity container, so entities are never
 *                           transferred even when this is {@code false}
 */
public record AssemblyOptions(
        Transform3d pose,
        BodyType type,
        PhysicsMaterial material,
        double massScale,
        double blockScale,
        boolean removeFromWorld,
        boolean copyBlockEntities,
        boolean keepEntities
) {
    public static final AssemblyOptions DEFAULTS = new AssemblyOptions(
            Transform3d.IDENTITY, BodyType.DYNAMIC, PhysicsMaterial.DEFAULT,
            1.0, 1.0, true, true, true);

    public AssemblyOptions {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(material, "material");
        massScale = Validate.positive(massScale, "massScale");
        blockScale = Validate.positive(blockScale, "blockScale");
    }

    public static AssemblyOptions defaults() {
        return DEFAULTS;
    }

    public AssemblyOptions withPose(Transform3d newPose) {
        return new AssemblyOptions(newPose, type, material, massScale, blockScale,
                removeFromWorld, copyBlockEntities, keepEntities);
    }

    public AssemblyOptions withType(BodyType newType) {
        return new AssemblyOptions(pose, newType, material, massScale, blockScale,
                removeFromWorld, copyBlockEntities, keepEntities);
    }

    public AssemblyOptions withMaterial(PhysicsMaterial newMaterial) {
        return new AssemblyOptions(pose, type, newMaterial, massScale, blockScale,
                removeFromWorld, copyBlockEntities, keepEntities);
    }

    public AssemblyOptions withMassScale(double newMassScale) {
        return new AssemblyOptions(pose, type, material, newMassScale, blockScale,
                removeFromWorld, copyBlockEntities, keepEntities);
    }

    public AssemblyOptions withBlockScale(double newBlockScale) {
        return new AssemblyOptions(pose, type, material, massScale, newBlockScale,
                removeFromWorld, copyBlockEntities, keepEntities);
    }

    public AssemblyOptions withRemoveFromWorld(boolean remove) {
        return new AssemblyOptions(pose, type, material, massScale, blockScale,
                remove, copyBlockEntities, keepEntities);
    }

    public AssemblyOptions withBlockEntities(boolean copy) {
        return new AssemblyOptions(pose, type, material, massScale, blockScale,
                removeFromWorld, copy, keepEntities);
    }

    public AssemblyOptions withKeepEntities(boolean keep) {
        return new AssemblyOptions(pose, type, material, massScale, blockScale,
                removeFromWorld, copyBlockEntities, keep);
    }
}
