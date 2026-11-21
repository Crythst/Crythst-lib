package io.mikaple.crythst.client.texture;

/**
 * Pure-Java colourisation of a grayscale mask with one or two base colours.
 *
 * <p>The user-facing mapping is, for every output RGB channel {@code R} and a
 * grayscale mask value {@code h} in {@code [0, 255]}:</p>
 *
 * <pre>
 * H = 255
 * f(R, h) = (1 - |2h/H - 1|) * R + floor(2h/H) * (h - H/2)
 * </pre>
 *
 * <p>This class implements exactly that expression. The weight and the lift term
 * are evaluated in {@code double} precision, and the result is rounded and
 * clamped once, when the channel is converted back to an {@code int}. Rounding
 * uses {@link Math#round(double)} (half-up for the non-negative values produced
 * here); the result is clamped to {@code 0..255}. Inputs outside
 * {@code 0..255} are rejected rather than silently wrapped, because silently
 * clamping a mask hides authoring errors.</p>
 *
 * <p>The source alpha is never part of the formula: it is copied from the source
 * pixel. In the outline pass, fully transparent pixels are returned unchanged.</p>
 *
 * <p>This package deliberately has no Minecraft dependency so the maths can be
 * tested with plain JUnit. See {@code docs/grayscale-colorization.md}.</p>
 */
public final class GrayscaleColorizer {

    /** The value {@code H} from the formula. */
    public static final int H = 255;

    /** {@code H/2}; the centre of the mask range. */
    public static final double HALF_H = H / 2.0;

    private GrayscaleColorizer() {
    }

    /**
     * Applies the formula to one RGB channel.
     *
     * <p>Piecewise, for integer mask values:</p>
     * <ul>
     *     <li>{@code h = 0}: {@code 0}</li>
     *     <li>{@code h = 1..127}: {@code (2h/255) * R}</li>
     *     <li>{@code h = 128..254}: {@code (2 - 2h/255) * R + (h - 127.5)}</li>
     *     <li>{@code h = 255}: {@code 255}, because {@code floor(2 * 255/255) = 2}</li>
     * </ul>
     *
     * @param colorChannel source colour channel {@code R}, in {@code 0..255}
     * @param gray         grayscale mask value {@code h}, in {@code 0..255}
     * @return the tinted channel, in {@code 0..255}
     * @throws IllegalArgumentException if either argument is outside {@code 0..255}
     */
    public static int tintChannel(int colorChannel, int gray) {
        requireByte(colorChannel, "colorChannel");
        requireByte(gray, "gray");

        final double ramp = (2.0 * gray) / H;
        final double weight = 1.0 - Math.abs(ramp - 1.0);
        final double lift = Math.floor(ramp) * (gray - HALF_H);
        final double value = weight * colorChannel + lift;

        return clampToByte((int) Math.round(value));
    }

    /**
     * Tints one ARGB pixel with {@code color}, preserving the source alpha.
     *
     * <p>{@code color}'s alpha is intentionally ignored. The grayscale value is
     * read from the source pixel with {@link #grayOf(int)}.</p>
     *
     * @param argb  source pixel in ARGB order
     * @param color base colour whose RGB channels are used
     * @return tinted pixel in ARGB order
     */
    public static int tintArgb(int argb, int color) {
        final int gray = grayOf(argb);
        final int alpha = (argb >>> 24) & 0xFF;

        final int red = tintChannel((color >>> 16) & 0xFF, gray);
        final int green = tintChannel((color >>> 8) & 0xFF, gray);
        final int blue = tintChannel(color & 0xFF, gray);

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /**
     * Tints a whole ARGB image with one colour.
     *
     * <p>The input array is not modified; a new array is returned.</p>
     *
     * @param argbPixels source pixels in ARGB order
     * @param color      base colour whose RGB channels are used
     * @return a new tinted pixel array
     */
    public static int[] tint(int[] argbPixels, int color) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        final int[] result = new int[argbPixels.length];
        for (int i = 0; i < argbPixels.length; i++) {
            result[i] = tintArgb(argbPixels[i], color);
        }
        return result;
    }

    /**
     * Convenience overload for a square image. The side length is inferred from
     * {@code argbPixels.length}, which must be a perfect square.
     *
     * <p>Uses four-neighbour edge detection and an alpha threshold of {@code 0}
     * (only fully transparent pixels are treated as empty).</p>
     *
     * @see #tintWithOutline(int[], int, int, int, int, OutlineMask.Mode, int)
     */
    public static int[] tintWithOutline(int[] argbPixels, int primaryColor, int outlineColor) {
        final int side = squareSide(argbPixels);
        return tintWithOutline(argbPixels, side, side, primaryColor, outlineColor,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 0);
    }

    /**
     * Tints an image, using {@code outlineColor} for covered pixels that touch
     * empty space and {@code primaryColor} for the interior.
     *
     * <p>Uses four-neighbour edge detection and an alpha threshold of {@code 0}.</p>
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor) {
        return tintWithOutline(argbPixels, width, height, primaryColor, outlineColor,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 0);
    }

    /**
     * Tints an image with a configurable outline neighbourhood.
     *
     * <p>Uses an alpha threshold of {@code 0} (only fully transparent pixels are
     * treated as empty).</p>
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor,
                                        OutlineMask.Mode mode) {
        return tintWithOutline(argbPixels, width, height, primaryColor, outlineColor, mode, 0);
    }

    /**
     * Tints an image with a configurable outline neighbourhood and alpha threshold.
     *
     * <p>A pixel is part of the outline when its own alpha is greater than
     * {@code alphaThreshold} and at least one neighbour (inside the image or just
     * outside it) has alpha less than or equal to the threshold. Pixels with
     * alpha zero are copied unchanged, so a transparent background stays exactly
     * as it was authored.</p>
     *
     * @param argbPixels     source pixels in ARGB order, row-major
     * @param width          image width in pixels
     * @param height         image height in pixels
     * @param primaryColor   base colour for interior pixels
     * @param outlineColor   base colour for outline pixels
     * @param mode           four- or eight-neighbour edge detection
     * @param alphaThreshold {@code 0..255}; pixels at or below this alpha count as empty
     * @return a new tinted pixel array
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        int primaryColor, int outlineColor,
                                        OutlineMask.Mode mode, int alphaThreshold) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        if (mode == null) {
            throw new NullPointerException("mode must not be null");
        }
        if (alphaThreshold < 0 || alphaThreshold > 255) {
            throw new IllegalArgumentException("alphaThreshold must be in 0..255: " + alphaThreshold);
        }

        final boolean[] outline = OutlineMask.detect(
                argbPixels, width, height, mode, alphaThreshold);
        final int[] result = new int[argbPixels.length];
        for (int i = 0; i < argbPixels.length; i++) {
            final int argb = argbPixels[i];
            if ((argb >>> 24) == 0) {
                // Keep transparent pixels byte-for-byte identical. Tinting their
                // hidden RGB would only make straight/premultiplied conversions
                // and future mask composition harder to reason about.
                result[i] = argb;
            } else {
                result[i] = tintArgb(argb, outline[i] ? outlineColor : primaryColor);
            }
        }
        return result;
    }

    /**
     * Palette-based outline overload.
     *
     * @see #tintWithOutline(int[], int, int, int, int, OutlineMask.Mode, int)
     */
    public static int[] tintWithOutline(int[] argbPixels, int width, int height,
                                        GrayscalePalette palette, OutlineMask.Mode mode) {
        if (palette == null) {
            throw new NullPointerException("palette must not be null");
        }
        return tintWithOutline(argbPixels, width, height,
                palette.primaryArgb(), palette.outlineArgb(), mode, 0);
    }

    /**
     * Reads the grayscale value {@code h} from a source pixel.
     *
     * <p>For a strict grayscale mask {@code r == g == b}, so the red channel is
     * sufficient. This method returns the maximum of the three channels instead:
     * it is identical on a strict mask and degrades gracefully on an
     * accidentally coloured or anti-aliased mask. Source alpha does not
     * participate.</p>
     */
    public static int grayOf(int argb) {
        final int red = (argb >>> 16) & 0xFF;
        final int green = (argb >>> 8) & 0xFF;
        final int blue = argb & 0xFF;
        return Math.max(red, Math.max(green, blue));
    }

    private static int squareSide(int[] argbPixels) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        final int side = (int) Math.sqrt(argbPixels.length);
        if (side * side != argbPixels.length) {
            throw new IllegalArgumentException(
                    "argbPixels.length must be a perfect square for the 3-argument overload: "
                            + argbPixels.length + "; use the width/height overload instead");
        }
        return side;
    }

    private static void requireByte(int value, String name) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be in 0..255: " + value);
        }
    }

    private static int clampToByte(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }
}
