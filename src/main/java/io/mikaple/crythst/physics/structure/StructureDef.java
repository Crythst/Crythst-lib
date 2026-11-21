package io.mikaple.crythst.physics.structure;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.util.Validate;

import java.util.Objects;

/**
 * Description of a moving voxel structure. The pose maps structure-local metres
 * (voxel coordinates multiplied by {@link VoxelColliderOptions#blockScale()}) to
 * world space.
 */
public final class StructureDef {
    private final VoxelVolume volume;
    private final Transform3d pose;
    private final BodyType type;
    private final PhysicsMaterial material;
    private final CollisionFilter filter;
    private final VoxelColliderOptions colliderOptions;
    private final double massScale;
    private final double linearDamping;
    private final double angularDamping;
    private final boolean ccd;
    private final Object userData;

    private StructureDef(Builder builder) {
        this.volume = builder.volume;
        this.pose = builder.pose;
        this.type = builder.type;
        this.material = builder.material;
        this.filter = builder.filter;
        this.colliderOptions = builder.colliderOptions;
        this.massScale = builder.massScale;
        this.linearDamping = builder.linearDamping;
        this.angularDamping = builder.angularDamping;
        this.ccd = builder.ccd;
        this.userData = builder.userData;
    }

    public static Builder builder(VoxelVolume volume) {
        return new Builder(volume);
    }

    public VoxelVolume volume() {
        return volume;
    }

    public Transform3d pose() {
        return pose;
    }

    public BodyType type() {
        return type;
    }

    public PhysicsMaterial material() {
        return material;
    }

    public CollisionFilter filter() {
        return filter;
    }

    public VoxelColliderOptions colliderOptions() {
        return colliderOptions;
    }

    public double massScale() {
        return massScale;
    }

    public double linearDamping() {
        return linearDamping;
    }

    public double angularDamping() {
        return angularDamping;
    }

    public boolean ccd() {
        return ccd;
    }

    public Object userData() {
        return userData;
    }

    public static final class Builder {
        private final VoxelVolume volume;
        private Transform3d pose = Transform3d.IDENTITY;
        private BodyType type = BodyType.DYNAMIC;
        private PhysicsMaterial material = PhysicsMaterial.DEFAULT;
        private CollisionFilter filter = CollisionFilter.DEFAULT;
        private VoxelColliderOptions colliderOptions = VoxelColliderOptions.DEFAULT;
        private double massScale = 1.0;
        private double linearDamping;
        private double angularDamping;
        private boolean ccd;
        private Object userData;

        private Builder(VoxelVolume volume) {
            this.volume = Validate.notNull(volume, "volume");
        }

        public Builder pose(Transform3d newPose) {
            this.pose = Objects.requireNonNull(newPose, "pose");
            return this;
        }

        public Builder type(BodyType newType) {
            this.type = Objects.requireNonNull(newType, "type");
            return this;
        }

        public Builder material(PhysicsMaterial newMaterial) {
            this.material = Objects.requireNonNull(newMaterial, "material");
            return this;
        }

        public Builder filter(CollisionFilter newFilter) {
            this.filter = Objects.requireNonNull(newFilter, "filter");
            return this;
        }

        public Builder colliderOptions(VoxelColliderOptions options) {
            this.colliderOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder massScale(double scale) {
            this.massScale = Validate.positive(scale, "massScale");
            return this;
        }

        public Builder damping(double linear, double angular) {
            this.linearDamping = Validate.nonNegative(linear, "linearDamping");
            this.angularDamping = Validate.nonNegative(angular, "angularDamping");
            return this;
        }

        public Builder ccd(boolean enabled) {
            this.ccd = enabled;
            return this;
        }

        public Builder userData(Object newUserData) {
            this.userData = newUserData;
            return this;
        }

        public StructureDef build() {
            return new StructureDef(this);
        }
    }
}
