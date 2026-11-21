package io.mikaple.crythst.client.texture;

/**
 * Alpha-coverage edge detection for the two-colour outline pass.
 *
 * <p>A covered pixel is an outline pixel when it is adjacent to empty space. The
 * empty space may be inside the image (a hole) or just outside its border:
 * out-of-bounds neighbours are always treated as transparent. Fully transparent
 * pixels themselves are never marked.</p>
 *
 * <p>The mask is computed from the source alpha before any tinting. Tinting does
 * not change alpha, but computing the mask first keeps the outline independent
 * of the chosen colours and makes it possible to reuse the same mask for several
 * palettes.</p>
 */
public final class OutlineMask {

    /** Neighbourhood used for edge detection. */
    public enum Mode {
        /** Up, down, left and right only. */
        FOUR_NEIGHBOURS,
        /** All eight surrounding pixels, including diagonals. */
        EIGHT_NEIGHBOURS
    }

    private static final int[][] FOUR_OFFSETS = {
            {0, -1}, {0, 1}, {-1, 0}, {1, 0}
    };

    private static final int[][] EIGHT_OFFSETS = {
            {0, -1}, {0, 1}, {-1, 0}, {1, 0},
            {-1, -1}, {1, -1}, {-1, 1}, {1, 1}
    };

    private OutlineMask() {
    }

    /**
     * Detects outline pixels with four-neighbour connectivity and an alpha
     * threshold of {@code 0}.
     */
    public static boolean[] detect(int[] argbPixels, int width, int height) {
        return detect(argbPixels, width, height, Mode.FOUR_NEIGHBOURS, 0);
    }

    /**
     * Detects outline pixels with the given neighbourhood and an alpha threshold
     * of {@code 0}.
     */
    public static boolean[] detect(int[] argbPixels, int width, int height, Mode mode) {
        return detect(argbPixels, width, height, mode, 0);
    }

    /**
     * Detects outline pixels.
     *
     * @param argbPixels     source pixels in ARGB order, row-major
     * @param width          image width in pixels
     * @param height         image height in pixels
     * @param mode           four- or eight-neighbour edge detection
     * @param alphaThreshold {@code 0..255}; neighbours with alpha at or below
     *                       this value count as empty
     * @return a new mask where {@code true} means "draw this pixel with the
     *         outline colour"; fully transparent pixels are always {@code false}
     */
    public static boolean[] detect(int[] argbPixels, int width, int height,
                                   Mode mode, int alphaThreshold) {
        if (argbPixels == null) {
            throw new NullPointerException("argbPixels must not be null");
        }
        if (mode == null) {
            throw new NullPointerException("mode must not be null");
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width and height must be >= 0: "
                    + width + "x" + height);
        }
        if (alphaThreshold < 0 || alphaThreshold > 255) {
            throw new IllegalArgumentException("alphaThreshold must be in 0..255: " + alphaThreshold);
        }
        if ((long) width * height != argbPixels.length) {
            throw new IllegalArgumentException("width * height (" + width + " * " + height
                    + ") must equal argbPixels.length (" + argbPixels.length + ")");
        }

        final int[][] offsets = mode == Mode.EIGHT_NEIGHBOURS ? EIGHT_OFFSETS : FOUR_OFFSETS;
        final boolean[] outline = new boolean[argbPixels.length];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                if (((argbPixels[index] >>> 24) & 0xFF) <= alphaThreshold) {
                    continue;
                }

                for (int[] offset : offsets) {
                    final int nx = x + offset[0];
                    final int ny = y + offset[1];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        outline[index] = true;
                        break;
                    }
                    final int neighbourAlpha = (argbPixels[ny * width + nx] >>> 24) & 0xFF;
                    if (neighbourAlpha <= alphaThreshold) {
                        outline[index] = true;
                        break;
                    }
                }
            }
        }
        return outline;
    }
}
