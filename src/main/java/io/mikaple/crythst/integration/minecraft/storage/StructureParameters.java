package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

/**
 * The non-geometry half of a saved physics structure.
 *
 * <p>A live {@link PhysicsStructure} exposes its body type, material, CCD flag,
 * mass scale and block scale, but not its collision filter or damping. Auto
 * discovery therefore has to fall back to the defaults for the properties the
 * public API does not expose. Structures restored from disk keep the values
 * that were serialized here, so a save/load cycle is lossless for everything
 * this record contains.</p>
 */
public record StructureParameters(
        BodyType type,
        PhysicsMaterial material,
        CollisionFilter filter,
        VoxelColliderOptions colliderOptions,
        double massScale,
        double linearDamping,
        double angularDamping,
        boolean ccd
) {
    public StructureParameters {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(colliderOptions, "colliderOptions");
        if (!Double.isFinite(massScale) || massScale <= 0.0) {
            throw new IllegalArgumentException("massScale must be positive: " + massScale);
        }
        if (!Double.isFinite(linearDamping) || linearDamping < 0.0) {
            throw new IllegalArgumentException("linearDamping must not be negative: " + linearDamping);
        }
        if (!Double.isFinite(angularDamping) || angularDamping < 0.0) {
            throw new IllegalArgumentException("angularDamping must not be negative: " + angularDamping);
        }
    }

    /**
     * Defaults for a structure that was created before persistence was
     * registered.
     */
    public static StructureParameters defaults() {
        return new StructureParameters(BodyType.DYNAMIC, PhysicsMaterial.DEFAULT,
                CollisionFilter.DEFAULT, VoxelColliderOptions.DEFAULT,
                1.0, 0.0, 0.0, false);
    }

    /**
     * Reads as much as possible from a live structure.
     *
     * <p>The collision filter, damping and the greedy-merge/max-cell collider
     * options are not exposed by {@link PhysicsStructure}; they are replaced by
     * defaults. {@code userData} is deliberately never persisted.</p>
     */
    public static StructureParameters from(PhysicsStructure structure) {
        Objects.requireNonNull(structure, "structure");
        final VoxelColliderOptions defaults = VoxelColliderOptions.DEFAULT
                .withBlockScale(structure.blockScale());
        return new StructureParameters(
                structure.body().type(),
                structure.body().material(),
                CollisionFilter.DEFAULT,
                defaults,
                structure.massScale(),
                0.0,
                0.0,
                structure.body().ccd());
    }

    public StructureDef.Builder applyTo(StructureDef.Builder builder) {
        return builder
                .type(type)
                .material(material)
                .filter(filter)
                .colliderOptions(colliderOptions)
                .massScale(massScale)
                .damping(linearDamping, angularDamping)
                .ccd(ccd);
    }

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());

        final CompoundTag physicsMaterial = new CompoundTag();
        physicsMaterial.putDouble("Friction", material.friction());
        physicsMaterial.putDouble("Restitution", material.restitution());
        physicsMaterial.putDouble("RollingFriction", material.rollingFriction());
        physicsMaterial.putDouble("SpinningFriction", material.spinningFriction());
        physicsMaterial.putDouble("Density", material.density());
        tag.put("Material", physicsMaterial);

        final CompoundTag collisionFilter = new CompoundTag();
        collisionFilter.putInt("Group", filter.group());
        collisionFilter.putInt("CollidesWith", filter.collidesWith());
        tag.put("Filter", collisionFilter);

        final CompoundTag collider = new CompoundTag();
        collider.putBoolean("GreedyMerge", colliderOptions.greedyMerge());
        collider.putBoolean("MergeDifferentMaterials", colliderOptions.mergeDifferentMaterials());
        collider.putDouble("BlockScale", colliderOptions.blockScale());
        collider.putInt("MaxCells", colliderOptions.maxCells());
        tag.put("Collider", collider);

        tag.putDouble("MassScale", massScale);
        tag.putDouble("LinearDamping", linearDamping);
        tag.putDouble("AngularDamping", angularDamping);
        tag.putBoolean("Ccd", ccd);
        return tag;
    }

    public static StructureParameters fromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return defaults();
        }
        final BodyType type = parseBodyType(tag.getStringOr("Type", BodyType.DYNAMIC.name()));

        final CompoundTag materialTag = tag.getCompoundOrEmpty("Material");
        final PhysicsMaterial material = new PhysicsMaterial(
                materialTag.getDoubleOr("Friction", PhysicsMaterial.DEFAULT.friction()),
                materialTag.getDoubleOr("Restitution", PhysicsMaterial.DEFAULT.restitution()),
                materialTag.getDoubleOr("RollingFriction", PhysicsMaterial.DEFAULT.rollingFriction()),
                materialTag.getDoubleOr("SpinningFriction", PhysicsMaterial.DEFAULT.spinningFriction()),
                positiveOr(materialTag.getDoubleOr("Density", PhysicsMaterial.DEFAULT.density()),
                        PhysicsMaterial.DEFAULT.density()));

        final CompoundTag filterTag = tag.getCompoundOrEmpty("Filter");
        final CollisionFilter filter = parseFilter(
                filterTag.getIntOr("Group", CollisionFilter.DEFAULT.group()),
                filterTag.getIntOr("CollidesWith", CollisionFilter.DEFAULT.collidesWith()));

        final CompoundTag colliderTag = tag.getCompoundOrEmpty("Collider");
        final VoxelColliderOptions collider = new VoxelColliderOptions(
                colliderTag.getBooleanOr("GreedyMerge", VoxelColliderOptions.DEFAULT.greedyMerge()),
                colliderTag.getBooleanOr("MergeDifferentMaterials",
                        VoxelColliderOptions.DEFAULT.mergeDifferentMaterials()),
                positiveOr(colliderTag.getDoubleOr("BlockScale",
                        VoxelColliderOptions.DEFAULT.blockScale()), VoxelColliderOptions.DEFAULT.blockScale()),
                Math.max(1, colliderTag.getIntOr("MaxCells", VoxelColliderOptions.DEFAULT.maxCells())));

        return new StructureParameters(
                type,
                material,
                filter,
                collider,
                positiveOr(tag.getDoubleOr("MassScale", 1.0), 1.0),
                nonNegativeOr(tag.getDoubleOr("LinearDamping", 0.0), 0.0),
                nonNegativeOr(tag.getDoubleOr("AngularDamping", 0.0), 0.0),
                tag.getBooleanOr("Ccd", false));
    }

    private static BodyType parseBodyType(String value) {
        try {
            return BodyType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return BodyType.DYNAMIC;
        }
    }

    private static CollisionFilter parseFilter(int group, int collidesWith) {
        final int safeGroup = group <= 0 || (group & ~0xFFFF) != 0
                ? CollisionFilter.DEFAULT.group() : group;
        return new CollisionFilter(safeGroup, collidesWith & 0xFFFF);
    }

    private static double positiveOr(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOr(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}
