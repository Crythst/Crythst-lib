package io.mikaple.crythst.client.texture;

/**
 * Named grayscale-to-colour mappings understood by
 * {@link SmoothGrayscaleColorizer}.
 *
 * <p>The original {@link GrayscaleColorizer} remains the compatibility
 * implementation and is not changed by this enum. {@link #LEGACY} reproduces
 * its formula bit-for-bit; the other constants are alternative mappings with
 * different visual goals.</p>
 *
 * <p>All formulas use the same reading of the mask: {@code h = 0} is the
 * darkest input, {@code h = 255} is the brightest input, and roughly
 * {@code h = 127.5} is the base colour. Each output RGB channel is computed
 * independently from the corresponding channel of the base colour. Source alpha
 * is always copied through by {@link SmoothGrayscaleColorizer}; palette alpha is
 * never used.</p>
 *
 * @see SmoothGrayscaleColorizer
 * @see GrayscaleColorizer
 */
public enum GrayscaleFormula {

    /**
     * The user's original formula, preserved exactly for compatibility:
     *
     * <pre>
     * f(R, h) = (1 - |2h/H - 1|) * R + floor(2h/H) * (h - H/2)
     * </pre>
     *
     * <p>It has deliberate jumps at {@code h = 127/128} and {@code h = 254/255}.
     * {@link SmoothGrayscaleColorizer} delegates this constant directly to
     * {@link GrayscaleColorizer#tintChannel(int, int)}, so the values, rounding
     * and validation are identical.</p>
     */
    LEGACY("Exact compatibility with GrayscaleColorizer"),

    /**
     * A C1-continuous black &rarr; base colour &rarr; white ramp.
     *
     * <p>Each half is a cubic smoothstep. The derivative is zero at
     * {@code h = 0}, at the middle and at {@code h = 255}, so there is no floor
     * jump and no visible crease at the base colour. This is the general-purpose
     * smooth option; it is less blocky than a smooth linear ramp, but also less
     * "vanilla" than {@link #VANILLA_BANDS}.</p>
     */
    SMOOTH_RAMP("C1-continuous smoothstep ramp, black to base to white"),

    /**
     * A palette-ramp style mapping: mask values are quantized into 3&ndash;8
     * equal bands, then each band is painted with a flat tone.
     *
     * <p>With the default four bands the tones are black, dark primary, primary
     * and a near-white tinted highlight. With three bands they are black,
     * primary and highlight. This mimics the small number of flat shade bands
     * used by vanilla armour and equipment textures. The number of bands is
     * configurable through the {@code bands} argument of
     * {@link SmoothGrayscaleColorizer}; values outside
     * {@link #MIN_VANILLA_BANDS}..{@link #MAX_VANILLA_BANDS} are rejected.</p>
     */
    VANILLA_BANDS("Quantized black / dark primary / primary / highlight ramp"),

    /**
     * A plain multiply: {@code f = round(R * h / 255)}.
     *
     * <p>The mask modulates brightness without adding white. This preserves the
     * hue of the primary and is useful for armour-like textures whose brightest
     * authored shade is the base colour rather than a white highlight. If a
     * near-white top shade is wanted, use {@link #VANILLA_BANDS} or
     * {@link #LINEAR_LIGHT}.</p>
     */
    MULTIPLY("Multiply the primary by the mask, preserving hue"),

    /**
     * A piecewise-linear black &rarr; base colour &rarr; white ramp with an
     * additive white highlight on the upper half.
     *
     * <p>For {@code t = h/255 &lt;= 0.5} the output is
     * {@code R * 2t}; above the middle it is
     * {@code R + (255 - R) * (2t - 1)}. It reaches white at {@code h = 255}
     * without the original formula's discontinuity, but keeps a stronger,
     * more linear contrast than {@link #SMOOTH_RAMP}.</p>
     */
    LINEAR_LIGHT("Piecewise-linear ramp with an additive white highlight");

    /** Default number of bands used by {@link #VANILLA_BANDS}. */
    public static final int DEFAULT_VANILLA_BANDS = 4;

    /** Smallest supported {@link #VANILLA_BANDS} band count. */
    public static final int MIN_VANILLA_BANDS = 3;

    /** Largest supported {@link #VANILLA_BANDS} band count. */
    public static final int MAX_VANILLA_BANDS = 8;

    private final String description;

    GrayscaleFormula(String description) {
        this.description = description;
    }

    /**
     * A short human-readable description, useful for tooling and documentation.
     */
    public String description() {
        return description;
    }
}
