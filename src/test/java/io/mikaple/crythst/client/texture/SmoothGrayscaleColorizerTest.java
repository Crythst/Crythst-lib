package io.mikaple.crythst.client.texture;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmoothGrayscaleColorizerTest {

    private static final int CYAN = 0xFF00FFFF;
    private static final int PURPLE = 0xFF8000FF;
    private static final int WHITE = 0xFFFFFFFF;

    private static final SmoothGrayscaleColorizer.BlendSpace SRGB =
            SmoothGrayscaleColorizer.BlendSpace.SRGB;
    private static final SmoothGrayscaleColorizer.BlendSpace LINEAR =
            SmoothGrayscaleColorizer.BlendSpace.LINEAR;

    @Test
    void legacyIsBitIdenticalToTheOriginalColorizer() {
        for (int channel = 0; channel <= 255; channel++) {
            for (int gray = 0; gray <= 255; gray++) {
                final int expected = GrayscaleColorizer.tintChannel(channel, gray);
                assertEquals(expected,
                        SmoothGrayscaleColorizer.tintChannel(
                                channel, gray, GrayscaleFormula.LEGACY),
                        "defaults R=" + channel + ", h=" + gray);
                assertEquals(expected,
                        SmoothGrayscaleColorizer.tintChannel(
                                channel, gray, GrayscaleFormula.LEGACY, 7, LINEAR),
                        "explicit bands/space R=" + channel + ", h=" + gray);
            }
        }
    }

    @Test
    void legacyKeepsTheDocumentedJumpsAndNonMonotonicTopEnd() {
        assertEquals(0, channel(0, 127, GrayscaleFormula.LEGACY));
        assertEquals(1, channel(0, 128, GrayscaleFormula.LEGACY));
        assertEquals(254, channel(255, 127, GrayscaleFormula.LEGACY));
        assertEquals(255, channel(255, 128, GrayscaleFormula.LEGACY));
        assertEquals(129, channel(255, 254, GrayscaleFormula.LEGACY));
        assertEquals(255, channel(255, 255, GrayscaleFormula.LEGACY));
        assertTrue(channel(255, 254, GrayscaleFormula.LEGACY)
                < channel(255, 128, GrayscaleFormula.LEGACY));
    }

    @Test
    void everyFormulaReachesItsBlackAndBrightEndpoints() {
        for (int channel : new int[]{0, 1, 40, 64, 128, 200, 254, 255}) {
            assertEquals(0, channel(channel, 0, GrayscaleFormula.SMOOTH_RAMP));
            assertEquals(255, channel(channel, 255, GrayscaleFormula.SMOOTH_RAMP));
            assertEquals(0, channel(channel, 0, GrayscaleFormula.LINEAR_LIGHT));
            assertEquals(255, channel(channel, 255, GrayscaleFormula.LINEAR_LIGHT));
            assertEquals(0, channel(channel, 0, GrayscaleFormula.VANILLA_BANDS));
            assertEquals(0, channel(channel, 0, GrayscaleFormula.MULTIPLY));
            assertEquals(channel, channel(channel, 255, GrayscaleFormula.MULTIPLY));
        }

        // The vanilla top band is a tinted near-white highlight; a pure white
        // primary is the only case that reaches exactly 255 for every channel.
        assertEquals(191, channel(0, 255, GrayscaleFormula.VANILLA_BANDS));
        assertEquals(241, channel(200, 255, GrayscaleFormula.VANILLA_BANDS));
        assertEquals(255, channel(255, 255, GrayscaleFormula.VANILLA_BANDS));
    }

    @Test
    void smoothRampPassesThroughTheBaseColourWithoutAMiddleJump() {
        for (int channel = 0; channel <= 255; channel++) {
            assertEquals(channel, channel(channel, 127, GrayscaleFormula.SMOOTH_RAMP),
                    "h=127 R=" + channel);
            assertEquals(channel, channel(channel, 128, GrayscaleFormula.SMOOTH_RAMP),
                    "h=128 R=" + channel);
        }

        // Exact smoothstep samples (sRGB blending, four bands are irrelevant).
        assertEquals(128, channel(255, 64, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(101, channel(200, 64, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(20, channel(40, 64, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(228, channel(200, 192, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(130, channel(0, 192, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(255, channel(200, 254, GrayscaleFormula.SMOOTH_RAMP));
    }

    @Test
    void smoothRampHasNoUnexplainedAdjacentJumps() {
        // The smoothstep's maximum slope is 3 output levels per mask step for
        // sRGB blending and 5 for linear-space blending (rounding included).
        for (int channel = 0; channel <= 255; channel++) {
            int previous = channel(channel, 0, GrayscaleFormula.SMOOTH_RAMP);
            int previousLinear = channel(channel, 0, GrayscaleFormula.SMOOTH_RAMP, LINEAR);
            for (int gray = 1; gray <= 255; gray++) {
                final int current = channel(channel, gray, GrayscaleFormula.SMOOTH_RAMP);
                final int currentLinear =
                        channel(channel, gray, GrayscaleFormula.SMOOTH_RAMP, LINEAR);
                assertTrue(Math.abs(current - previous) <= 3,
                        "sRGB jump at R=" + channel + ", h=" + gray
                                + ": " + previous + " -> " + current);
                assertTrue(Math.abs(currentLinear - previousLinear) <= 6,
                        "linear jump at R=" + channel + ", h=" + gray
                                + ": " + previousLinear + " -> " + currentLinear);
                previous = current;
                previousLinear = currentLinear;
            }
        }

        assertEquals(0, channel(0, 127, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(0, channel(0, 128, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(255, channel(255, 127, GrayscaleFormula.SMOOTH_RAMP));
        assertEquals(255, channel(255, 128, GrayscaleFormula.SMOOTH_RAMP));
    }

    @Test
    void nonLegacyFormulasAreMonotonicInTheMask() {
        for (GrayscaleFormula formula : List.of(
                GrayscaleFormula.SMOOTH_RAMP,
                GrayscaleFormula.VANILLA_BANDS,
                GrayscaleFormula.MULTIPLY,
                GrayscaleFormula.LINEAR_LIGHT)) {
            for (int channel = 0; channel <= 255; channel++) {
                int previous = channel(channel, 0, formula);
                for (int gray = 1; gray <= 255; gray++) {
                    final int current = channel(channel, gray, formula);
                    assertTrue(current >= previous,
                            formula + " decreased at R=" + channel + ", h=" + gray
                                    + ": " + previous + " -> " + current);
                    previous = current;
                }
            }
        }
    }

    @Test
    void vanillaBandsQuantizeIntoTheDocumentedFourBands() {
        // Four equal bands: 0..63 black, 64..127 dark primary,
        // 128..191 primary, 192..255 near-white highlight.
        assertEquals(0, channel(200, 63, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(100, channel(200, 64, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(100, channel(200, 127, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(200, channel(200, 128, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(200, channel(200, 191, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(241, channel(200, 192, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(241, channel(200, 255, GrayscaleFormula.VANILLA_BANDS, 4));

        assertEquals(0, channel(0, 191, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(191, channel(0, 192, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(128, channel(255, 64, GrayscaleFormula.VANILLA_BANDS, 4));
        assertEquals(255, channel(255, 192, GrayscaleFormula.VANILLA_BANDS, 4));
    }

    @Test
    void vanillaBandsAreConfigurableForThreeFiveAndEightBands() {
        // Three bands: 0..85 black, 86..170 primary, 171..255 highlight.
        assertEquals(0, channel(200, 85, GrayscaleFormula.VANILLA_BANDS, 3));
        assertEquals(200, channel(200, 86, GrayscaleFormula.VANILLA_BANDS, 3));
        assertEquals(200, channel(200, 170, GrayscaleFormula.VANILLA_BANDS, 3));
        assertEquals(241, channel(200, 171, GrayscaleFormula.VANILLA_BANDS, 3));

        // Five bands add intermediate primary shades: 0.5R, 0.75R, R.
        assertEquals(100, channel(200, 52, GrayscaleFormula.VANILLA_BANDS, 5));
        assertEquals(150, channel(200, 103, GrayscaleFormula.VANILLA_BANDS, 5));
        assertEquals(200, channel(200, 154, GrayscaleFormula.VANILLA_BANDS, 5));
        assertEquals(241, channel(200, 205, GrayscaleFormula.VANILLA_BANDS, 5));

        // Eight bands are the documented maximum and keep every middle tone
        // between 0.5R and R.
        final int eight = channel(200, 32, GrayscaleFormula.VANILLA_BANDS, 8);
        assertEquals(100, eight);
        assertEquals(241, channel(200, 255, GrayscaleFormula.VANILLA_BANDS, 8));
    }

    @Test
    void multiplyModulatesBrightnessWithoutAddingWhite() {
        assertEquals(0, channel(200, 0, GrayscaleFormula.MULTIPLY));
        assertEquals(50, channel(200, 64, GrayscaleFormula.MULTIPLY));
        assertEquals(100, channel(200, 127, GrayscaleFormula.MULTIPLY));
        assertEquals(100, channel(200, 128, GrayscaleFormula.MULTIPLY));
        assertEquals(151, channel(200, 192, GrayscaleFormula.MULTIPLY));
        assertEquals(200, channel(200, 255, GrayscaleFormula.MULTIPLY));
        assertEquals(0, channel(0, 255, GrayscaleFormula.MULTIPLY));
    }

    @Test
    void linearLightIsAStraightRampWithAWhiteEnd() {
        assertEquals(100, channel(200, 64, GrayscaleFormula.LINEAR_LIGHT));
        assertEquals(199, channel(200, 127, GrayscaleFormula.LINEAR_LIGHT));
        assertEquals(200, channel(200, 128, GrayscaleFormula.LINEAR_LIGHT));
        assertEquals(227, channel(200, 191, GrayscaleFormula.LINEAR_LIGHT));
        assertEquals(228, channel(200, 192, GrayscaleFormula.LINEAR_LIGHT));
        assertEquals(255, channel(200, 255, GrayscaleFormula.LINEAR_LIGHT));
        assertEquals(1, channel(0, 128, GrayscaleFormula.LINEAR_LIGHT));
    }

    @Test
    void channelsAreIndependentAndAlphaAlwaysComesFromTheSource() {
        final int source = 0x80A0B0C0;
        for (GrayscaleFormula formula : GrayscaleFormula.values()) {
            final int tinted = SmoothGrayscaleColorizer.tintArgb(
                    source, 0x00123456, formula, 4, SRGB);
            assertEquals(0x80, (tinted >>> 24) & 0xFF, formula + " alpha");
            assertEquals(channel(0x12, 0xC0, formula), (tinted >>> 16) & 0xFF,
                    formula + " red");
            assertEquals(channel(0x34, 0xC0, formula), (tinted >>> 8) & 0xFF,
                    formula + " green");
            assertEquals(channel(0x56, 0xC0, formula), tinted & 0xFF,
                    formula + " blue");
        }

        for (GrayscaleFormula formula : GrayscaleFormula.values()) {
            assertEquals(0, SmoothGrayscaleColorizer.tintArgb(
                    0x00123456, WHITE, formula, 4, SRGB) >>> 24);
        }
        assertEquals(0x80, SmoothGrayscaleColorizer.tintArgb(
                source, 0xFFFFFFFF, GrayscaleFormula.SMOOTH_RAMP) >>> 24);
        assertEquals(0x80, SmoothGrayscaleColorizer.tintArgb(
                source, 0xFFFFFFFF, GrayscaleFormula.VANILLA_BANDS, 6) >>> 24);
    }

    @Test
    void tintDoesNotMutateItsInput() {
        final int[] input = {0xFF000000, 0xFF808080, 0xFFFFFFFF, 0x00123456};
        final int[] copy = input.clone();

        for (GrayscaleFormula formula : GrayscaleFormula.values()) {
            final int[] result = SmoothGrayscaleColorizer.tint(input, CYAN, formula);
            assertNotSame(input, result);
            assertArrayEquals(copy, input, formula + " mutated its input");
            for (int i = 0; i < input.length; i++) {
                assertEquals(SmoothGrayscaleColorizer.tintArgb(input[i], CYAN, formula),
                        result[i], formula + " pixel " + i);
            }
        }
    }

    @Test
    void outlinePassUsesTheChosenFormulaAndLeavesTransparencyAlone() {
        final int primary = 0xFFFF0000;
        final int outline = 0xFF0000FF;
        final int[] input = filledBlock5x5(0xC0, 0x00345678);

        for (GrayscaleFormula formula : GrayscaleFormula.values()) {
            final int[] result = SmoothGrayscaleColorizer.tintWithOutline(
                    input, 5, 5, primary, outline, formula);
            final int[] copy = input.clone();
            assertArrayEquals(copy, input, formula + " mutated its input");

            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    final int index = y * 5 + x;
                    final boolean inBlock = x >= 1 && x <= 3 && y >= 1 && y <= 3;
                    if (!inBlock) {
                        assertEquals(input[index], result[index],
                                formula + " changed transparent pixel " + x + "," + y);
                        assertEquals(0, result[index] >>> 24);
                    } else {
                        final boolean ring = x == 1 || x == 3 || y == 1 || y == 3;
                        final int expected = SmoothGrayscaleColorizer.tintArgb(
                                input[index], ring ? outline : primary, formula);
                        assertEquals(expected, result[index],
                                formula + " wrong tint at " + x + "," + y);
                    }
                }
            }

            assertEquals(0xFF, result[2 * 5 + 2] >>> 24);
        }
    }

    @Test
    void outlinePassSupportsBandsBlendSpaceAndNeighbourhoodModes() {
        final int[] input = new int[9];
        Arrays.fill(input, 0xFF808080);

        final int[] vanilla = SmoothGrayscaleColorizer.tintWithOutline(
                input, 3, 3, CYAN, PURPLE, GrayscaleFormula.VANILLA_BANDS,
                3, LINEAR, OutlineMask.Mode.EIGHT_NEIGHBOURS, 0);
        final int[] smooth = SmoothGrayscaleColorizer.tintWithOutline(
                input, 3, 3, CYAN, PURPLE, GrayscaleFormula.SMOOTH_RAMP,
                3, LINEAR, OutlineMask.Mode.EIGHT_NEIGHBOURS, 0);

        // In a fully filled 3x3 image the centre is the only interior pixel with
        // either neighbourhood; the eight border pixels are outline pixels.
        for (int i = 0; i < 9; i++) {
            final int base = (i == 4) ? CYAN : PURPLE;
            assertEquals(SmoothGrayscaleColorizer.tintArgb(
                    input[i], base, GrayscaleFormula.VANILLA_BANDS, 3, LINEAR),
                    vanilla[i], "vanilla at " + i);
            assertEquals(SmoothGrayscaleColorizer.tintArgb(
                    input[i], base, GrayscaleFormula.SMOOTH_RAMP, 3, LINEAR),
                    smooth[i], "smooth at " + i);
        }

        // A plus shape distinguishes four- from eight-neighbour detection.
        final int[] plus = new int[9];
        plus[1] = plus[3] = plus[4] = plus[5] = plus[7] = 0xFFA0A0A0;
        final int[] four = SmoothGrayscaleColorizer.tintWithOutline(
                plus, 3, 3, CYAN, PURPLE, GrayscaleFormula.SMOOTH_RAMP,
                OutlineMask.Mode.FOUR_NEIGHBOURS);
        final int[] eight = SmoothGrayscaleColorizer.tintWithOutline(
                plus, 3, 3, CYAN, PURPLE, GrayscaleFormula.SMOOTH_RAMP,
                OutlineMask.Mode.EIGHT_NEIGHBOURS);
        assertEquals(SmoothGrayscaleColorizer.tintArgb(
                plus[4], CYAN, GrayscaleFormula.SMOOTH_RAMP), four[4]);
        assertNotEquals(four[4], eight[4]);
        assertEquals(SmoothGrayscaleColorizer.tintArgb(
                plus[4], PURPLE, GrayscaleFormula.SMOOTH_RAMP), eight[4]);
    }

    @Test
    void paletteOverloadMatchesTheExplicitColourOverload() {
        final GrayscalePalette palette = GrayscalePalette.of(0x112233, 0xFF445566);
        final int[] input = filledBlock5x5(0x80, 0);

        for (GrayscaleFormula formula : GrayscaleFormula.values()) {
            assertArrayEquals(
                    SmoothGrayscaleColorizer.tintWithOutline(
                            input, 5, 5, palette.primaryArgb(), palette.outlineArgb(),
                            formula, 4, SRGB, OutlineMask.Mode.FOUR_NEIGHBOURS, 0),
                    SmoothGrayscaleColorizer.tintWithOutline(
                            input, 5, 5, palette, formula));
            assertArrayEquals(
                    SmoothGrayscaleColorizer.tintWithOutline(
                            input, 5, 5, palette.primaryArgb(), palette.outlineArgb(),
                            formula, 4, SRGB, OutlineMask.Mode.EIGHT_NEIGHBOURS, 0),
                    SmoothGrayscaleColorizer.tintWithOutline(
                            input, 5, 5, palette, formula,
                            OutlineMask.Mode.EIGHT_NEIGHBOURS));
        }
    }

    @Test
    void linearBlendingIsLighterButKeepsTheAnchors() {
        assertEquals(64, channel(128, 64, GrayscaleFormula.SMOOTH_RAMP, SRGB));
        assertEquals(93, channel(128, 64, GrayscaleFormula.SMOOTH_RAMP, LINEAR));
        assertEquals(101, channel(200, 64, GrayscaleFormula.SMOOTH_RAMP, SRGB));
        assertEquals(147, channel(200, 64, GrayscaleFormula.SMOOTH_RAMP, LINEAR));

        assertEquals(64, channel(128, 64, GrayscaleFormula.LINEAR_LIGHT, SRGB));
        assertEquals(93, channel(128, 64, GrayscaleFormula.LINEAR_LIGHT, LINEAR));

        // Anchors and the base colour are identical in both blend spaces.
        assertEquals(0, channel(128, 0, GrayscaleFormula.SMOOTH_RAMP, LINEAR));
        assertEquals(255, channel(128, 255, GrayscaleFormula.SMOOTH_RAMP, LINEAR));
        assertEquals(128, channel(128, 127, GrayscaleFormula.SMOOTH_RAMP, LINEAR));
        assertEquals(128, channel(128, 128, GrayscaleFormula.SMOOTH_RAMP, LINEAR));

        // The other formulas are defined in sRGB and ignore the blend space.
        for (GrayscaleFormula formula : List.of(
                GrayscaleFormula.LEGACY,
                GrayscaleFormula.VANILLA_BANDS,
                GrayscaleFormula.MULTIPLY)) {
            assertEquals(channel(128, 64, formula, SRGB),
                    channel(128, 64, formula, LINEAR), formula + " ignored space");
        }
    }

    @Test
    void gammaHelpersRoundTripAndMatchKnownValues() {
        assertEquals(0.0, SmoothGrayscaleColorizer.srgbToLinear(0), 0.0);
        assertEquals(1.0, SmoothGrayscaleColorizer.srgbToLinear(255), 0.0);
        assertEquals(0, SmoothGrayscaleColorizer.linearToSrgbByte(0.0));
        assertEquals(255, SmoothGrayscaleColorizer.linearToSrgbByte(1.0));
        assertEquals(0.2158605, SmoothGrayscaleColorizer.srgbToLinear(128), 1.0e-6);
        for (int channel = 0; channel <= 255; channel++) {
            assertEquals(channel, SmoothGrayscaleColorizer.linearToSrgbByte(
                    SmoothGrayscaleColorizer.srgbToLinear(channel)));
        }
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintChannel(
                        -1, 0, GrayscaleFormula.SMOOTH_RAMP));
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintChannel(
                        0, 256, GrayscaleFormula.SMOOTH_RAMP));
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintChannel(
                        0, 0, GrayscaleFormula.VANILLA_BANDS, 2));
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintChannel(
                        0, 0, GrayscaleFormula.VANILLA_BANDS, 9));
        assertThrows(NullPointerException.class,
                () -> SmoothGrayscaleColorizer.tintChannel(0, 0, null));
        assertThrows(NullPointerException.class,
                () -> SmoothGrayscaleColorizer.tintChannel(
                        0, 0, GrayscaleFormula.SMOOTH_RAMP, 4, null));
        assertThrows(NullPointerException.class,
                () -> SmoothGrayscaleColorizer.tint(null, WHITE,
                        GrayscaleFormula.SMOOTH_RAMP));
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintWithOutline(
                        new int[6], WHITE, 0xFF000000, GrayscaleFormula.SMOOTH_RAMP));
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintWithOutline(
                        new int[6], 2, 2, WHITE, 0xFF000000,
                        GrayscaleFormula.SMOOTH_RAMP));
        assertThrows(IllegalArgumentException.class,
                () -> SmoothGrayscaleColorizer.tintWithOutline(
                        new int[9], 3, 3, WHITE, 0xFF000000,
                        GrayscaleFormula.SMOOTH_RAMP, 4, SRGB,
                        OutlineMask.Mode.FOUR_NEIGHBOURS, 256));
        assertThrows(NullPointerException.class,
                () -> SmoothGrayscaleColorizer.tintWithOutline(
                        new int[9], 3, 3, null, GrayscaleFormula.SMOOTH_RAMP));
    }

    private static int channel(int colorChannel, int gray, GrayscaleFormula formula) {
        return SmoothGrayscaleColorizer.tintChannel(colorChannel, gray, formula);
    }

    private static int channel(int colorChannel, int gray, GrayscaleFormula formula,
                               int bands) {
        return SmoothGrayscaleColorizer.tintChannel(colorChannel, gray, formula, bands);
    }

    private static int channel(int colorChannel, int gray, GrayscaleFormula formula,
                               SmoothGrayscaleColorizer.BlendSpace space) {
        return SmoothGrayscaleColorizer.tintChannel(
                colorChannel, gray, formula, SmoothGrayscaleColorizer.DEFAULT_BANDS, space);
    }

    private static int[] filledBlock5x5(int gray, int transparentPayload) {
        final int[] pixels = new int[25];
        Arrays.fill(pixels, transparentPayload & 0x00FFFFFF);
        final int opaqueGray = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 3; x++) {
                pixels[y * 5 + x] = opaqueGray;
            }
        }
        return pixels;
    }
}
