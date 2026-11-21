package io.mikaple.crythst.client.texture;

/**
 * The two base colours used by {@link GrayscaleColorizer}: a primary fill and an
 * outline.
 *
 * <p>Palette alpha is validated rather than used. The tint formula only consumes
 * the RGB channels; the output alpha always comes from the source pixel. To
 * avoid a palette whose alpha silently differs from the pixels it produces,
 * {@code 0xRRGGBB} literals (alpha byte {@code 0x00}) are accepted and normalised
 * to opaque, fully opaque {@code 0xAARRGGBB} values are accepted unchanged, and
 * partially transparent palette entries are rejected.</p>
 *
 * @param primaryArgb primary colour in ARGB order, normalised to alpha {@code 0xFF}
 * @param outlineArgb outline colour in ARGB order, normalised to alpha {@code 0xFF}
 */
public record GrayscalePalette(int primaryArgb, int outlineArgb) {

    public GrayscalePalette {
        primaryArgb = requireOpaque("primaryArgb", primaryArgb);
        outlineArgb = requireOpaque("outlineArgb", outlineArgb);
    }

    /**
     * Factory mirroring the record constructor, useful for readable call sites.
     */
    public static GrayscalePalette of(int primaryArgb, int outlineArgb) {
        return new GrayscalePalette(primaryArgb, outlineArgb);
    }

    private static int requireOpaque(String name, int argb) {
        final int alpha = (argb >>> 24) & 0xFF;
        if (alpha != 0x00 && alpha != 0xFF) {
            throw new IllegalArgumentException(name
                    + " must be opaque (0xFFRRGGBB) or an 0xRRGGBB literal; alpha was 0x"
                    + Integer.toHexString(alpha));
        }
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }
}
