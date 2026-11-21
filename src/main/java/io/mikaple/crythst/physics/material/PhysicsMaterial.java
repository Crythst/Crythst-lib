package io.mikaple.crythst.physics.material;

import io.mikaple.crythst.util.Validate;

/**
 * Surface and mass properties of a body.
 *
 * @param friction        Coulomb friction coefficient
 * @param restitution     bounce coefficient (0 = inelastic, 1 = perfectly elastic)
 * @param rollingFriction rolling resistance
 * @param spinningFriction spinning resistance
 * @param density         mass per cubic metre; only used when a body derives its
 *                        mass from its shape volume
 */
public record PhysicsMaterial(
        double friction,
        double restitution,
        double rollingFriction,
        double spinningFriction,
        double density
) {
    public static final PhysicsMaterial DEFAULT = new PhysicsMaterial(0.6, 0.0, 0.0, 0.0, 1000.0);
    public static final PhysicsMaterial STONE = new PhysicsMaterial(0.7, 0.0, 0.02, 0.02, 2400.0);
    public static final PhysicsMaterial DIRT = new PhysicsMaterial(0.8, 0.0, 0.05, 0.05, 1600.0);
    public static final PhysicsMaterial WOOD = new PhysicsMaterial(0.6, 0.05, 0.02, 0.02, 700.0);
    public static final PhysicsMaterial METAL = new PhysicsMaterial(0.4, 0.05, 0.01, 0.01, 7800.0);
    public static final PhysicsMaterial ICE = new PhysicsMaterial(0.05, 0.0, 0.0, 0.0, 900.0);
    public static final PhysicsMaterial RUBBER = new PhysicsMaterial(0.9, 0.65, 0.1, 0.1, 1100.0);

    public PhysicsMaterial {
        Validate.nonNegative(friction, "friction");
        Validate.nonNegative(restitution, "restitution");
        Validate.nonNegative(rollingFriction, "rollingFriction");
        Validate.nonNegative(spinningFriction, "spinningFriction");
        Validate.positive(density, "density");
    }

    public static PhysicsMaterial of(double friction, double restitution) {
        return new PhysicsMaterial(friction, restitution, 0.0, 0.0, 1000.0);
    }

    public PhysicsMaterial withDensity(double newDensity) {
        return new PhysicsMaterial(friction, restitution, rollingFriction, spinningFriction, newDensity);
    }

    public PhysicsMaterial withFriction(double newFriction) {
        return new PhysicsMaterial(newFriction, restitution, rollingFriction, spinningFriction, density);
    }

    public PhysicsMaterial withRestitution(double newRestitution) {
        return new PhysicsMaterial(friction, newRestitution, rollingFriction, spinningFriction, density);
    }
}
