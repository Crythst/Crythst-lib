package io.mikaple.crythst.integration.minecraft.mass;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.util.Validate;

/**
 * Fully resolved physical properties of one cubic metre of a block.
 *
 * <p>Mass is kilograms per block. Minecraft blocks are one metre on a side, so
 * the mass is also the density in {@code kg/m³} used by
 * {@link #toMaterial()}. Friction is a Coulomb coefficient, restitution is the
 * bounce coefficient in {@code [0, 1]}, and the rolling/spinning values are
 * optional resistance coefficients (zero disables them).</p>
 *
 * <p>{@code buoyancyScale} multiplies the displaced volume used by the buoyancy
 * pass. A value of {@code 1.0} means normal displacement; values below one make
 * a block less buoyant and values above one make it more buoyant. The value is
 * carried here so gameplay code can read it without another data format.</p>
 *
 * @param mass             kilograms per block (and density in {@code kg/m³})
 * @param friction         Coulomb friction coefficient
 * @param restitution      bounce coefficient, {@code 0..1}
 * @param rollingFriction  rolling resistance
 * @param spinningFriction spinning resistance
 * @param buoyancyScale    multiplier for displaced volume, {@code 1.0} = normal
 */
public record BlockPhysicsProfile(
        double mass,
        double friction,
        double restitution,
        double rollingFriction,
        double spinningFriction,
        double buoyancyScale
) {
    /** Fallback used when neither a data pack nor the config provides values. */
    public static final BlockPhysicsProfile DEFAULT = new BlockPhysicsProfile(1000.0, 0.7, 0.0, 0.0, 0.0, 1.0);

    public BlockPhysicsProfile {
        Validate.positive(mass, "mass");
        Validate.nonNegative(friction, "friction");
        Validate.nonNegative(restitution, "restitution");
        if (restitution > 1.0) {
            throw new IllegalArgumentException("restitution must be at most 1: " + restitution);
        }
        Validate.nonNegative(rollingFriction, "rollingFriction");
        Validate.nonNegative(spinningFriction, "spinningFriction");
        Validate.nonNegative(buoyancyScale, "buoyancyScale");
    }

    /**
     * Builds the fallback profile from the mod configuration. This is what the
     * data pack's {@code defaults} object overrides when it is present.
     */
    public static BlockPhysicsProfile fromConfig(CrythstConfig config) {
        Validate.notNull(config, "config");
        return new BlockPhysicsProfile(
                config.defaultBlockMass,
                config.defaultFriction,
                config.defaultRestitution,
                0.0,
                0.0,
                1.0);
    }

    /**
     * Converts to the physics backend's surface material. Density is the block
     * mass per cubic metre; voxel structures pass explicit per-block masses to
     * Bullet, but loose bodies derived from shape volume use this density.
     */
    public PhysicsMaterial toMaterial() {
        return new PhysicsMaterial(friction, restitution, rollingFriction, spinningFriction, mass);
    }

    public BlockPhysicsProfile withBuoyancyScale(double newBuoyancyScale) {
        return new BlockPhysicsProfile(mass, friction, restitution, rollingFriction, spinningFriction,
                newBuoyancyScale);
    }
}
