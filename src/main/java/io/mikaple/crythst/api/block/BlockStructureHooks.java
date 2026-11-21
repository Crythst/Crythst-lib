package io.mikaple.crythst.api.block;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable bundle of the block hooks a single block or block-entity type can
 * contribute to Crythst structure physics.
 *
 * <p>Use {@link #builder()} for registration through
 * {@link StructureBlockHooks}. Fields left {@code null} fall back to the next
 * source in the lookup chain (registered bundle, block interfaces, block-entity
 * type registration, vanilla behaviour).</p>
 */
public record BlockStructureHooks(
        @Nullable BlockStructureCollisionShape collisionShape,
        @Nullable BlockStructureCustomCenterOfMass centerOfMass,
        @Nullable BlockStructureDynamicCollider dynamicCollider,
        @Nullable BlockStructureAssemblyListener assemblyListener,
        @Nullable BlockStructureBlockChangeListener blockChangeListener,
        @Nullable BlockStructurePhysicsProperties physicsProperties
) {
    public static final BlockStructureHooks EMPTY =
            new BlockStructureHooks(null, null, null, null, null, null);

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return collisionShape == null && centerOfMass == null && dynamicCollider == null
                && assemblyListener == null && blockChangeListener == null
                && physicsProperties == null;
    }

    /**
     * Returns a copy where every non-null field of {@code override} wins.
     */
    public BlockStructureHooks mergeOver(BlockStructureHooks override) {
        if (override == null || override.isEmpty()) {
            return this;
        }
        return new BlockStructureHooks(
                override.collisionShape != null ? override.collisionShape : collisionShape,
                override.centerOfMass != null ? override.centerOfMass : centerOfMass,
                override.dynamicCollider != null ? override.dynamicCollider : dynamicCollider,
                override.assemblyListener != null ? override.assemblyListener : assemblyListener,
                override.blockChangeListener != null ? override.blockChangeListener : blockChangeListener,
                override.physicsProperties != null ? override.physicsProperties : physicsProperties);
    }

    /**
     * Creates a bundle with only a collision-shape hook.
     */
    public static BlockStructureHooks of(BlockStructureCollisionShape hook) {
        return builder().collisionShape(hook).build();
    }

    /**
     * Creates a bundle with only a custom centre-of-mass hook.
     */
    public static BlockStructureHooks of(BlockStructureCustomCenterOfMass hook) {
        return builder().centerOfMass(hook).build();
    }

    /**
     * Creates a bundle with only a dynamic collider hook.
     */
    public static BlockStructureHooks of(BlockStructureDynamicCollider hook) {
        return builder().dynamicCollider(hook).build();
    }

    /**
     * Creates a bundle with only an assembly listener.
     */
    public static BlockStructureHooks of(BlockStructureAssemblyListener hook) {
        return builder().assemblyListener(hook).build();
    }

    /**
     * Creates a bundle with only a block-change listener.
     */
    public static BlockStructureHooks of(BlockStructureBlockChangeListener hook) {
        return builder().blockChangeListener(hook).build();
    }

    /**
     * Creates a bundle with only a physics-properties hook.
     */
    public static BlockStructureHooks of(BlockStructurePhysicsProperties hook) {
        return builder().physicsProperties(hook).build();
    }

    /**
     * Mutable builder for a {@link BlockStructureHooks} bundle.
     */
    public static final class Builder {
        private BlockStructureCollisionShape collisionShape;
        private BlockStructureCustomCenterOfMass centerOfMass;
        private BlockStructureDynamicCollider dynamicCollider;
        private BlockStructureAssemblyListener assemblyListener;
        private BlockStructureBlockChangeListener blockChangeListener;
        private BlockStructurePhysicsProperties physicsProperties;

        private Builder() {
        }

        public Builder collisionShape(BlockStructureCollisionShape hook) {
            this.collisionShape = hook;
            return this;
        }

        public Builder centerOfMass(BlockStructureCustomCenterOfMass hook) {
            this.centerOfMass = hook;
            return this;
        }

        public Builder dynamicCollider(BlockStructureDynamicCollider hook) {
            this.dynamicCollider = hook;
            return this;
        }

        public Builder assemblyListener(BlockStructureAssemblyListener hook) {
            this.assemblyListener = hook;
            return this;
        }

        public Builder blockChangeListener(BlockStructureBlockChangeListener hook) {
            this.blockChangeListener = hook;
            return this;
        }

        public Builder physicsProperties(BlockStructurePhysicsProperties hook) {
            this.physicsProperties = hook;
            return this;
        }

        public BlockStructureHooks build() {
            return new BlockStructureHooks(collisionShape, centerOfMass, dynamicCollider,
                    assemblyListener, blockChangeListener, physicsProperties);
        }
    }
}
