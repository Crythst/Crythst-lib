package io.mikaple.crythst.client.texture;

/**
 * Grayscale colourisation with selectable transfer functions.
 *
 * <p>This class complements {@link GrayscaleColorizer}. It keeps the original
 * class and its exact formula untouched, and adds the mappings described by
 * {@link GrayscaleFormula}:</p>
 *
 * <ul>
 *     <li>{@link GrayscaleFormula#LEGACY} &mdash; bit-identical to
 *         {@link GrayscaleColorizer#tintChannel(int, int)}.</li>
 *     <li>{@link GrayscaleFormula#SMOOTH_RAMP} &mdash; C1-continuous
 *         black &rarr; base colour &rarr; white smoothstep ramp.</li>
 *     <li>{@link GrayscaleFormula#VANILLA_BANDS} &mdash; quantized palette
 *         ramp with a configurable number of shade bands.</li>
 *     <li>{@link GrayscaleFormula#MULTIPLY} &mdash; hue-preserving multiply,
 *         useful for armour shading.</li>
 *     <li>{@link GrayscaleFormula#LINEAR_LIGHT} &mdash; piecewise-linear ramp
 *         with an additive white highlight.</li>
 * </ul>
 *
 * <h2>Math</h2>
 *
 * <p>Let {@code H = 255}, {@code t = h/H}, and {@code R} be one channel of the
 * base colour. For the smooth ramp, define the cubic smoothstep</p>
 *
 * <pre>
 * S(u) = u^2 * (3 - 2u),   0 &lt;= u &lt;= 1
 * </pre>
 *
 * <p>and then</p>
 *
 * <pre>
 * f_smooth(R, h) = S(2t) * R                         for t &lt;= 1/2
 *                = R + S(2t - 1) * (255 - R)         for t &gt;  1/2
 * </pre>
 *
 * <p>Both halves have zero derivative at the join, so the ramp is C1. It also
 * passes exactly through black at {@code h = 0}, the base colour at
 * {@code h = 127} and {@code h = 128}, and white at {@code h = 255}.</p>
 *
 * <p>The linear-light ramp uses the same anchors but replaces the smoothstep
 * with the identity:</p>
 *
 * <pre>
 * f_linear_light(R, h) = 2t * R                      for t &lt;= 1/2
 *                      = R + (2t - 1) * (255 - R)   for t &gt;  1/2
 * </pre>
 *
 * <p>The vanilla-band mapping divides {@code 0..255} into {@code n} bands with
 * {@code band = min(n - 1, floor(h * n / 256))}. Band {@code 0} is black, the
 * last band is {@code R + 0.75 * (255 - R)}, and the middle bands step through
 * factors of the primary from {@code 0.5R} up to {@code R} (three bands have a
 * single middle band at {@code R}). The top band is therefore a near-white,
 * hue-preserving highlight; a white base colour makes it exactly white. The
 * multiply formula is simply {@code round(R * t)}.</p>
 *
 * <h2>Alpha and gamma</h2>
 *
 * <p>Alpha is copied from the source pixel; the base colour's alpha is ignored.
 * Pixels are treated as straight (non-premultiplied) ARGB. The outline overloads
 * copy fully transparent source pixels unchanged, exactly like
 * {@link GrayscaleColorizer#tintWithOutline}.</p>
 *
 * <p>{@link BlendSpace#LINEAR} performs the interpolation towards black or
 * white in linear sRGB instead of the stored sRGB bytes. This makes midtones
 * lighter and is physically more correct for compositing. It is applied to
 * {@link GrayscaleFormula#SMOOTH_RAMP} and
 * {@link GrayscaleFormula#LINEAR_LIGHT}; the exact legacy formula, the
 * quantized vanilla bands and the multiply formula are defined in 8-bit sRGB
 * space and ignore the requested blend space.</p>
 */
public final class SmoothGrayscaleColorizer {

    /** Alias for the default number of vanilla bands. */
    public static final int DEFAULT_BANDS = GrayscaleFormula.DEFAULT_VANILLA_BANDS;

    /** Fraction of the primary used for the darkest non-black vanilla band. */
    public static final double VANILLA_DARK_FACTOR = 0.5;

    /**
     * Fraction of the distance from the primary to white used for the top
     * vanilla band. {@code 0.75} gives a tinted, near-white highlight rather
     * than a flat pure-white band.
     */
    public static final double VANILLA_HIGHLIGHT_MIX = 0.75;

    /** The {@code H} constant shared with {@link GrayscaleColorizer}. */
    public static final int H = GrayscaleColorizer.H;

    /** Colour space in which blends towards black/white are performed. */
    public enum BlendSpace {
        /** Blend the stored 8-bit sRGB values directly (the default). */
        SRGB,
        /** Decode to linear sRGB, blend, then re-encode to sRGB. */
        LINEAR
    }

    private SmoothGrayscaleColorizer() {
    }

    // ---------------------------------------------------------------------
    // Per-channel entry points
    // ---------------------------------------------------------------------

    /**
     * Applies {@code formula} to one channel using four vanilla bands and sRGB
     * blending.
     *
     * @param colorChannel base-colour channel {@code R}, in {@code 0..255}
     * @param gray         grayscale mask value {@code h}, in {@code 0..255}
     * @param formula      transfer function; must not be {@code null}
     * @return the mapped channel, in {@code 0..255}
     */
    public static int tintChannel(int colorChannel, int gray, GrayscaleFormula formula) {
        return tintChannel(colorChannel, gray, formula, DEFAULT_BANDS, BlendSpace.SRGB);
    }

    /**
     * Applies {@code formula} to one channel with an explicit vanilla band
     * count.
     *
     * @param bands number of bands, only used by
     *              {@link GrayscaleFormula#VANILLA_BANDS}; must be in
     *              {@link GrayscaleFormula#MIN_VANILLA_BANDS}..{@link
     *              GrayscaleFormula#MAX_VANILLA_BANDS}
     */
    public static int tintChannel(int colorChannel, int gray,
                                  GrayscaleFormula formula, int bands) {
        return tintChannel(colorChannel, gray, formula, bands, BlendSpace.SRGB);
    }

    /**
     * Applies {@code formula} to one channel.
     *
     * @param colorChannel base-colour channel {@code R}, in {@code 0..255}
     * @param gray         grayscale mask value {@code h}, in {@code 0..255}
     * @param formula      transfer function; must not be {@code null}
     * @param bands        number of vanilla bands, in {@code 3..8}
     * @param space        blend space; used only by the ramp formulas
     * @return the mapped channel, in {@code 0..255}
     * @throws IllegalArgumentException if a channel, {@code gray} or {@code bands}
     *                                  is outside its allowed range
     * @throws NullPointerException     if {@code formula} or {@code space} is null
     */
    public static int tintChannel(int colorChannel, int gray,
                                  GrayscaleFormula formula, int bands,
                                  BlendSpace space) {
        requireByte(colorChannel, "colorChannel");
        requireByte(gray, "gray");
        if (formula == null) {
            throw new NullPointerException("formula must not be null");
        }
        if (space == null) {
            throw new NullPointerException("space must not be null");
        }
        requireBands(bands);

        return switch (formula) {
            case LEGACY -> GrayscaleColorizer.tintChannel(colorChannel, gray);
            case SMOOTH_RAMP -> smoothRamp(colorChannel, gray, space);
            case VANILLA_BANDS -> vanillaBands(colorChannel, gray, bands);
            case MULTIPLY -> multiply(colorChannel, gray);
            case LINEAR_LIGHT -> linearLight(colorChannel, gray, space);
        };
    }

    // ---------------------------------------------------------------------
    // ARGB entry points
    // ---------------------------------------------------------------------

    /**
     * Tints one ARGB pixel with {@code color}, preserving the source alpha.
     *
     * @see #tintChannel(int, int, GrayscaleFormula)
     */
    public static int tintArgb(int argb, int color, GrayscaleFormula formula) {
        return tintArgb(argb, color, formula, DEFAULT_BANDS, BlendSpace.SRGB);
    }

    /**
     * Tints one ARGB pixel with an explicit vanilla band count.
     */
    public static int tintArgb(int argb, int color, GrayscaleFormula formula, int bands) {
        return tintArgb(argb, color, formula, bands, BlendSpace.SRGB);
    }

    /**
     * Tints one ARGB pixel, preserving the source alpha and ignoring the base
     * colour's alpha.
     *
     * @param argb    source pixel in ARGB order
     * @param color   base colour whose RGB channels are used
     * @param formula transfer function; must not be {@code null}
     * @param bands   number of vanilla bands, in {@code 3..8}
     * @param space   blend space; used only by the ramp formulas
     * @return the tinted pixel in ARGB order
     */
    public static int tintArgb(int argb, int color, GrayscaleFormula formula,
                               int bands, BlendSpace space) {
        final int gray = GrayscaleColorizer.grayOf(argb);
        final int alpha = (argb >>> 24) & 0xFF;

        final int red = tintChannel((color >>> 16) & 0xFF, gray, formula, bands, space);
        final int green = tintChannel((color >>> 8) & 0xFF, gray, formula, bands, space);
        final int blue = tintChannel(color & 0xFF, gray, formula, bands, space);

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /**
     * Tints a whole ARGB image with one colour. The input array is not
     * modified; a new array is returned.
     */
    public static int[] tint(int[] argbPixels, int color, GrayscaleFormula formula) {
        return tint(argbPixels, color, formula, DEFAULT_BANDS, BlendSpace.SRGB);
    }

    /**
     * Tints a whole ARGB image with an explicit vanilla band count.
     */
    public static int[] tint(int[] argbPixels, int color,
                             GrayscaleFormula formula, int bands) {
        return tint(argbPixels, color, formula, bands, BlendSpace.SRGB);
    }

    /**
     * Tints a whole ARGB image with one colour and an explicit blend space.
     *
     * @param argbPixels source pixels in ARGB order
     * @param color      base colour whose RGB channels are used
     * @param formula    transfer function; must not be {@code null}
     * @param bands      number of vanilla bands, in {@code 3..8}
     * @param space      blend space; used only by the ramp formulas
     * @return a new tinted pixel array
     */
    public static int[] tint(int[] argbPixels, int color,
                             GrayscaleFormula formula, int bands, BlendSpace space) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        final int[] result = new int[argbPixels.length];
        for (int i = 0; i < argbPixels.length; i++) {
            result[i] = tintArgb(argbPixels[i], color, formula, bands, space);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Outline-aware entry points
    // ---------------------------------------------------------------------

    /**
     * Tints a square image with its own outline mask.
     *
     * <p>The side length is inferred from the array length, which must be a
     * perfect square. Uses four-neighbour detection, alpha threshold {@code 0},
     * {@link #DEFAULT_BANDS} and {@link BlendSpace#SRGB}.</p>
     */
    public static int[] tintWithOutline(int[] argbPixels, int primaryColor,
                                        int outlineColor, GrayscaleFormula formula) {
        final int side = squareSide(argbPixels);
        return tintWithOutline(argbPixels, side, side, primaryColor, outlineColor,
                formula, DEFAULT_BANDS, BlendSpace.SRGB,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 0);
    }

    /**
     * Tints an image, using {@code outlineColor} for covered pixels that touch
     * empty space and {@code primaryColor} for the interior.
     *
     * <p>Uses four-neighbour detection, alpha threshold {@code 0},
     * {@link #DEFAULT_BANDS} and {@link BlendSpace#SRGB}.</p>
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor,
                                        GrayscaleFormula formula) {
        return tintWithOutline(argbPixels, width, height, primaryColor, outlineColor,
                formula, DEFAULT_BANDS, BlendSpace.SRGB,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 0);
    }

    /**
     * Tints an image with a configurable outline neighbourhood.
     *
     * <p>Uses alpha threshold {@code 0}, {@link #DEFAULT_BANDS} and
     * {@link BlendSpace#SRGB}.</p>
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor,
                                        GrayscaleFormula formula, OutlineMask.Mode mode) {
        return tintWithOutline(argbPixels, width, height, primaryColor, outlineColor,
                formula, DEFAULT_BANDS, BlendSpace.SRGB, mode, 0);
    }

    /**
     * Tints an image with a configurable outline neighbourhood and alpha
     * threshold.
     *
     * <p>Uses {@link #DEFAULT_BANDS} and {@link BlendSpace#SRGB}.</p>
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor,
                                        GrayscaleFormula formula, OutlineMask.Mode mode,
                                        int alphaThreshold) {
        return tintWithOutline(argbPixels, width, height, primaryColor, outlineColor,
                formula, DEFAULT_BANDS, BlendSpace.SRGB, mode, alphaThreshold);
    }

    /**
     * Full outline-aware tint pass.
     *
     * <p>A pixel is an outline pixel when its own alpha is greater than
     * {@code alphaThreshold} and at least one neighbour (inside the image or
     * just outside it) has alpha at or below the threshold. Fully transparent
     * pixels are copied byte-for-byte, so a transparent background is never
     * tinted or made visible.</p>
     *
     * @param argbPixels     source pixels in ARGB order, row-major
     * @param width          image width in pixels
     * @param height         image height in pixels
     * @param primaryColor   base colour for interior pixels
     * @param outlineColor   base colour for outline pixels
     * @param formula        transfer function; must not be {@code null}
     * @param bands          number of vanilla bands, in {@code 3..8}
     * @param space          blend space; used only by the ramp formulas
     * @param mode           four- or eight-neighbour edge detection
     * @param alphaThreshold {@code 0..255}; pixels at or below this alpha count
     *                       as empty
     * @return a new tinted pixel array
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor,
                                        GrayscaleFormula formula, int bands,
                                        BlendSpace space, OutlineMask.Mode mode,
                                        int alphaThreshold) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        if (formula == null) {
            throw new NullPointerException("formula must not be null");
        }
        if (space == null) {
            throw new NullPointerException("space must not be null");
        }
        if (mode == null) {
            throw new NullPointerException("mode must not be null");
        }
        if (alphaThreshold < 0 || alphaThreshold > 255) {
            throw new IllegalArgumentException(
                    "alphaThreshold must be in 0..255: " + alphaThreshold);
        }
        requireBands(bands);

        final boolean[] outline = OutlineMask.detect(
                argbPixels, width, height, mode, alphaThreshold);
        final int[] result = new int[argbPixels.length];
        for (int i = 0; i < argbPixels.length; i++) {
            final int argb = argbPixels[i];
            if ((argb >>> 24) == 0) {
                result[i] = argb;
            } else {
                result[i] = tintArgb(argb, outline[i] ? outlineColor : primaryColor,
                        formula, bands, space);
            }
        }
        return result;
    }

    /**
     * Palette-based outline overload using four-neighbour detection, alpha
     * threshold {@code 0}, {@link #DEFAULT_BANDS} and {@link BlendSpace#SRGB}.
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        GrayscalePalette palette, GrayscaleFormula formula) {
        if (palette == null) {
            throw new NullPointerException("palette must not be null");
        }
        return tintWithOutline(argbPixels, width, height,
                palette.primaryArgb(), palette.outlineArgb(),
                formula, DEFAULT_BANDS, BlendSpace.SRGB,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 0);
    }

    /**
     * Palette-based outline overload with an explicit neighbourhood.
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        GrayscalePalette palette, GrayscaleFormula formula,
                                        OutlineMask.Mode mode) {
        if (palette == null) {
            throw new NullPointerException("palette must not be null");
        }
        return tintWithOutline(argbPixels, width, height,
                palette.primaryArgb(), palette.outlineArgb(),
                formula, DEFAULT_BANDS, BlendSpace.SRGB, mode, 0);
    }

    // ---------------------------------------------------------------------
    // Transfer functions
    // ---------------------------------------------------------------------

    private static int smoothRamp(int channel, int gray, BlendSpace space) {
        if (gray == 0) {
            return 0;
        }
        if (gray == H) {
            return 255;
        }
        final double t = gray / (double) H;
        if (t <= 0.5) {
            return blend(0, channel, smoothstep(2.0 * t), space);
        }
        return blend(channel, 255, smoothstep(2.0 * t - 1.0), space);
    }

    private static int linearLight(int channel, int gray, BlendSpace space) {
        if (gray == 0) {
            return 0;
        }
        if (gray == H) {
            return 255;
        }
        final double t = gray / (double) H;
        if (t <= 0.5) {
            return blend(0, channel, 2.0 * t, space);
        }
        return blend(channel, 255, 2.0 * t - 1.0, space);
    }

    private static int multiply(int channel, int gray) {
        return roundToByte(channel * (gray / (double) H));
    }

    private static int vanillaBands(int channel, int gray, int bands) {
        // 256, not 255: the range is split into equal-width bands, and the last
        // band must contain h == 255. floor() is implicit in integer division.
        final int band = Math.min(bands - 1, (gray * bands) / (H + 1));
        if (band == 0) {
            return 0;
        }
        if (band == bands - 1) {
            return blend(channel, 255, VANILLA_HIGHLIGHT_MIX, BlendSpace.SRGB);
        }
        final double factor;
        if (bands == 3) {
            factor = 1.0;
        } else {
            factor = VANILLA_DARK_FACTOR
                    + (1.0 - VANILLA_DARK_FACTOR) * (band - 1) / (double) (bands - 3);
        }
        return roundToByte(channel * factor);
    }

    private static double smoothstep(double u) {
        return u * u * (3.0 - 2.0 * u);
    }

    private static int blend(int from, int to, double s, BlendSpace space) {
        if (space == BlendSpace.LINEAR) {
            final double fromLinear = srgbToLinear(from);
            final double toLinear = srgbToLinear(to);
            return linearToSrgbByte(fromLinear + (toLinear - fromLinear) * s);
        }
        return roundToByte(from + (to - from) * s);
    }

    /**
     * Converts one 8-bit sRGB channel to linear sRGB using the standard IEC
     * 61966-2-1 transfer function.
     *
     * <p>Package-private so tests can verify the gamma helper without making it
     * part of the public API.</p>
     */
    static double srgbToLinear(int channel) {
        final double c = channel / 255.0;
        if (c <= 0.04045) {
            return c / 12.92;
        }
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * Converts a linear-sRGB value in {@code 0..1} back to a rounded 8-bit sRGB
     * channel.
     *
     * <p>Package-private so tests can verify the gamma helper without making it
     * part of the public API.</p>
     */
    static int linearToSrgbByte(double linear) {
        final double clamped = Math.max(0.0, Math.min(1.0, linear));
        final double c;
        if (clamped <= 0.0031308) {
            c = 12.92 * clamped;
        } else {
            c = 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
        }
        return roundToByte(c * 255.0);
    }

    // ---------------------------------------------------------------------
    // Validation and helpers
    // ---------------------------------------------------------------------

    private static void requireByte(int value, String name) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be in 0..255: " + value);
        }
    }

    private static void requireBands(int bands) {
        if (bands < GrayscaleFormula.MIN_VANILLA_BANDS
                || bands > GrayscaleFormula.MAX_VANILLA_BANDS) {
            throw new IllegalArgumentException("bands must be in "
                    + GrayscaleFormula.MIN_VANILLA_BANDS + ".."
                    + GrayscaleFormula.MAX_VANILLA_BANDS + ": " + bands);
        }
    }

    private static int roundToByte(double value) {
        return clampToByte((int) Math.round(value));
    }

    private static int clampToByte(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    private static int squareSide(int[] argbPixels) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        final int side = (int) Math.sqrt(argbPixels.length);
        if (side * side != argbPixels.length) {
            throw new IllegalArgumentException(
                    "argbPixels.length must be a perfect square for this overload: "
                            + argbPixels.length + "; use the width/height overload instead");
        }
        return side;
    }
}
