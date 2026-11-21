package io.mikaple.crythst.integration.minecraft.buoyancy;

/**
 * Pure, Minecraft-independent helpers for fluid buoyancy.
 *
 * <p>All values use SI units: kilograms, cubic metres, newtons and
 * metres per second squared. Keeping the arithmetic here makes the force model
 * easy to test without constructing a server level.</p>
 */
public final class BuoyancyMath {
    private BuoyancyMath() {
    }

    /**
     * Clamps a sampled fraction to {@code [0, 1]}. Non-finite values become
     * zero so a bad sample can never inject NaN into a force.
     */
    public static double clampFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return 0.0;
        }
        return Math.clamp(fraction, 0.0, 1.0);
    }

    /**
     * Fraction of samples that were found in water.
     *
     * @param submergedSamples number of samples inside a water volume
     * @param totalSamples     number of samples attempted
     */
    public static double submergedFraction(int submergedSamples, int totalSamples) {
        if (totalSamples <= 0 || submergedSamples <= 0) {
            return 0.0;
        }
        if (submergedSamples >= totalSamples) {
            return 1.0;
        }
        return (double) submergedSamples / (double) totalSamples;
    }

    /**
     * Volume of a body that is under water.
     *
     * @param bodyVolume       total solid volume of the body in cubic metres
     * @param submergedFraction sampled fraction in {@code [0, 1]}
     */
    public static double submergedVolume(double bodyVolume, double submergedFraction) {
        if (!(bodyVolume > 0.0) || !Double.isFinite(bodyVolume)) {
            return 0.0;
        }
        return bodyVolume * clampFraction(submergedFraction);
    }

    /**
     * Mass of the water a submerged volume displaces.
     */
    public static double displacedMass(double waterDensity, double submergedVolume) {
        if (!(waterDensity > 0.0) || !Double.isFinite(waterDensity)
                || !(submergedVolume > 0.0) || !Double.isFinite(submergedVolume)) {
            return 0.0;
        }
        return waterDensity * submergedVolume;
    }

    /**
     * Average density of a body. Bodies without a usable volume are reported as
     * infinitely dense, which means they can never float by density alone.
     */
    public static double density(double bodyMass, double bodyVolume) {
        if (!(bodyVolume > 0.0) || !Double.isFinite(bodyVolume)) {
            return Double.POSITIVE_INFINITY;
        }
        if (!(bodyMass > 0.0)) {
            return 0.0;
        }
        return bodyMass / bodyVolume;
    }

    /**
     * Whether a body is less dense than the fluid and therefore floats once
     * fully submerged. Neutrally buoyant bodies (equal density) return
     * {@code false}; they are neither sinking nor rising in this model.
     */
    public static boolean floats(double bodyMass, double bodyVolume, double waterDensity) {
        final double bodyDensity = density(bodyMass, bodyVolume);
        return Double.isFinite(bodyDensity) && bodyDensity < waterDensity;
    }

    /**
     * Upward force exerted by the displaced water itself
     * ({@code displacedMass * g}). Bullet also applies the body's own weight,
     * so the resulting net force is
     * {@code (displacedMass - bodyMass) * g}. Apply this full force rather than
     * the net value: applying the net value on top of Bullet's gravity would
     * subtract the body's weight twice.
     */
    public static double buoyantForce(double waterDensity, double submergedVolume,
                                      double gravityMagnitude) {
        if (!(gravityMagnitude > 0.0) || !Double.isFinite(gravityMagnitude)) {
            return 0.0;
        }
        return displacedMass(waterDensity, submergedVolume) * gravityMagnitude;
    }

    /**
     * Upward force from the displaced water, minus the body's weight. This is
     * the resulting net force after Bullet's gravity integration, not an
     * additional force to apply. Values at or below zero are clamped to zero:
     * dense bodies simply keep sinking under normal gravity instead of
     * receiving an artificial downward "anti-buoyancy" force.
     *
     * <p>For a fully submerged end-stone block with {@code mass = 500 kg} in
     * water ({@code 1000 kg/m³}) the result is positive, so it rises.</p>
     *
     * @param gravityMagnitude magnitude of world gravity in m/s²
     */
    public static double netBuoyantForce(double waterDensity, double submergedVolume,
                                         double bodyMass, double gravityMagnitude) {
        if (!(gravityMagnitude > 0.0) || !Double.isFinite(gravityMagnitude)) {
            return 0.0;
        }
        final double displaced = displacedMass(waterDensity, submergedVolume);
        final double mass = Math.max(0.0, bodyMass);
        final double netMass = displaced - mass;
        if (!(netMass > 0.0)) {
            return 0.0;
        }
        return netMass * gravityMagnitude;
    }

    /**
     * Linear water-drag coefficient in kg/s. The force is
     * {@code -coefficient * linearVelocity}, so it always opposes motion.
     *
     * <p>{@code buoyancyDamping} is used as a viscous drag coefficient rather
     * than as a multiplier on the buoyant force. Multiplying the net lift by
     * the default {@code 0.5} would make a 500 kg/m³ end-stone block heavier
     * than the lift it receives and it would sink instead of floating.</p>
     */
    public static double dragCoefficient(double buoyancyDamping, double displacedMass) {
        if (!(buoyancyDamping > 0.0) || !Double.isFinite(buoyancyDamping)
                || !(displacedMass > 0.0) || !Double.isFinite(displacedMass)) {
            return 0.0;
        }
        return buoyancyDamping * displacedMass;
    }
}
