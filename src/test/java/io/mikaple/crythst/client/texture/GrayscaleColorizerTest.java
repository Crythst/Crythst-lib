package io.mikaple.crythst.client.texture;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrayscaleColorizerTest {

    @Test
    void formulaHitsBlackBaseColourAndWhiteEndpoints() {
        assertEquals(0, GrayscaleColorizer.tintChannel(255, 0));
        assertEquals(0, GrayscaleColorizer.tintChannel(0, 0));

        // h == 255 is special: floor(2 * 255 / 255) == 2, so every channel lifts to 255.
        assertEquals(255, GrayscaleColorizer.tintChannel(0, 255));
        assertEquals(255, GrayscaleColorizer.tintChannel(128, 255));
        assertEquals(255, GrayscaleColorizer.tintChannel(255, 255));

        // Around H/2 the result is the base colour (within the discrete 127/128 gap).
        assertEquals(254, GrayscaleColorizer.tintChannel(255, 127));
        assertEquals(255, GrayscaleColorizer.tintChannel(255, 128));
        assertEquals(199, GrayscaleColorizer.tintChannel(200, 127));
        assertEquals(200, GrayscaleColorizer.tintChannel(200, 128));
        assertEquals(100, GrayscaleColorizer.tintChannel(100, 127));
        assertEquals(100, GrayscaleColorizer.tintChannel(100, 128));
    }

    @Test
    void formulaCoversAllThreePiecewiseSegments() {
        // Low segment: f = (2h / 255) * R.
        assertEquals(128, GrayscaleColorizer.tintChannel(255, 64));
        assertEquals(25, GrayscaleColorizer.tintChannel(100, 32));
        assertEquals(0, GrayscaleColorizer.tintChannel(100, 0));

        // Middle segment: f = (2 - 2h / 255) * R + (h - 127.5).
        assertEquals(163, GrayscaleColorizer.tintChannel(200, 192));
        assertEquals(65, GrayscaleColorizer.tintChannel(0, 192));
        assertEquals(127, GrayscaleColorizer.tintChannel(0, 254));
        assertEquals(129, GrayscaleColorizer.tintChannel(255, 254));

        // Top endpoint / floor(2h/H) == 2 segment.
        assertEquals(255, GrayscaleColorizer.tintChannel(0, 255));
    }

    @Test
    void floorTermProducesTheDocumented127To128Discontinuity() {
        // floor(2 * 127 / 255) == 0, floor(2 * 128 / 255) == 1, so a +0.5 lift
        // appears at h == 128. This is the user's formula, not a bug to fix.
        assertEquals(0, GrayscaleColorizer.tintChannel(0, 127));
        assertEquals(1, GrayscaleColorizer.tintChannel(0, 128));
        assertEquals(254, GrayscaleColorizer.tintChannel(255, 127));
        assertEquals(255, GrayscaleColorizer.tintChannel(255, 128));
    }

    @Test
    void everyChannelCombinationStaysInByteRange() {
        for (int channel = 0; channel <= 255; channel++) {
            for (int gray = 0; gray <= 255; gray++) {
                final int value = GrayscaleColorizer.tintChannel(channel, gray);
                assertTrue(value >= 0 && value <= 255,
                        "out of range for R=" + channel + ", h=" + gray + ": " + value);
            }
        }
    }

    @Test
    void channelsAreIndependentAndAlphaComesFromTheSource() {
        final int source = 0x80A0B0C0;
        final int color = 0x00123456; // deliberately transparent colour: alpha is ignored
        final int tinted = GrayscaleColorizer.tintArgb(source, color);

        assertEquals(0x80, (tinted >>> 24) & 0xFF);
        assertEquals(GrayscaleColorizer.tintChannel(0x12, 0xC0), (tinted >>> 16) & 0xFF);
        assertEquals(GrayscaleColorizer.tintChannel(0x34, 0xC0), (tinted >>> 8) & 0xFF);
        assertEquals(GrayscaleColorizer.tintChannel(0x56, 0xC0), tinted & 0xFF);

        // A transparent source stays transparent.
        assertEquals(0, GrayscaleColorizer.tintArgb(0x00123456, 0xFFFFFFFF) >>> 24);
    }

    @Test
    void tintDoesNotMutateItsInput() {
        final int[] input = {0xFF000000, 0xFF808080, 0xFFFFFFFF, 0x00123456};
        final int[] copy = input.clone();

        final int[] result = GrayscaleColorizer.tint(input, 0xFF3366CC);

        assertNotSame(input, result);
        assertArrayEquals(copy, input);
        for (int i = 0; i < input.length; i++) {
            assertEquals(GrayscaleColorizer.tintArgb(input[i], 0xFF3366CC), result[i]);
        }
    }

    @Test
    void outlinePassTintsTheOuterRingAndLeavesTheBackgroundTransparent() {
        final int primary = 0xFFFF0000;
        final int outline = 0xFF0000FF;
        final int[] input = filledBlock5x5(0xC0, 0x00345678); // transparent payload must survive

        final int[] result = GrayscaleColorizer.tintWithOutline(input, 5, 5, primary, outline);

        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                final int index = y * 5 + x;
                final boolean inBlock = x >= 1 && x <= 3 && y >= 1 && y <= 3;
                if (!inBlock) {
                    assertEquals(input[index], result[index],
                            "transparent pixel changed at " + x + "," + y);
                    assertEquals(0, result[index] >>> 24);
                } else {
                    final boolean ring = x == 1 || x == 3 || y == 1 || y == 3;
                    final int expected = GrayscaleColorizer.tintArgb(
                            input[index], ring ? outline : primary);
                    assertEquals(expected, result[index], "wrong tint at " + x + "," + y);
                }
            }
        }

        final int centre = result[2 * 5 + 2];
        final int ring = result[1 * 5 + 1];
        assertNotEquals(ring, centre);
        assertEquals(0xFF, centre >>> 24);
    }

    @Test
    void threeByThreeFilledImageTreatsTheImageBorderAsEmptySpace() {
        final int primary = 0xFFFF0000;
        final int outline = 0xFF0000FF;
        final int[] input = new int[9];
        Arrays.fill(input, 0xFF808080);

        // The 3-argument overload infers a 3x3 square and uses 4-neighbour detection.
        final int[] result = GrayscaleColorizer.tintWithOutline(input, primary, outline);

        for (int i = 0; i < 9; i++) {
            if (i == 4) {
                assertEquals(GrayscaleColorizer.tintArgb(input[i], primary), result[i]);
            } else {
                assertEquals(GrayscaleColorizer.tintArgb(input[i], outline), result[i]);
            }
        }
    }

    @Test
    void fourAndEightNeighbourDetectionDifferOnADiagonalCorner() {
        // Plus shape: the centre has all four orthogonal neighbours covered, but
        // its diagonal corners are transparent.
        final int[] input = new int[9];
        final int opaque = 0xFFA0A0A0;
        input[1] = opaque;
        input[3] = opaque;
        input[4] = opaque;
        input[5] = opaque;
        input[7] = opaque;

        final boolean[] four = OutlineMask.detect(input, 3, 3, OutlineMask.Mode.FOUR_NEIGHBOURS);
        final boolean[] eight = OutlineMask.detect(input, 3, 3, OutlineMask.Mode.EIGHT_NEIGHBOURS);

        assertFalse(four[4], "centre must be interior with 4-neighbour detection");
        assertTrue(eight[4], "centre must be outline with 8-neighbour detection");

        final int primary = 0xFFFF0000;
        final int outline = 0xFF0000FF;
        final int[] fourTint = GrayscaleColorizer.tintWithOutline(
                input, 3, 3, primary, outline, OutlineMask.Mode.FOUR_NEIGHBOURS);
        final int[] eightTint = GrayscaleColorizer.tintWithOutline(
                input, 3, 3, primary, outline, OutlineMask.Mode.EIGHT_NEIGHBOURS);

        assertEquals(GrayscaleColorizer.tintArgb(input[4], primary), fourTint[4]);
        assertEquals(GrayscaleColorizer.tintArgb(input[4], outline), eightTint[4]);
    }

    @Test
    void alphaThresholdCanIgnoreNearlyTransparentCoverage() {
        final int[] input = new int[9];
        Arrays.fill(input, 0x40808080); // alpha 64

        final boolean[] strict = OutlineMask.detect(input, 3, 3,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 0);
        final boolean[] permissive = OutlineMask.detect(input, 3, 3,
                OutlineMask.Mode.FOUR_NEIGHBOURS, 128);

        assertTrue(strict[0]);
        assertFalse(permissive[0]);
    }

    @Test
    void outlinePassDoesNotMutateItsInputAndIsDeterministic() {
        final int[] input = filledBlock5x5(0x80, 0);
        final int[] copy = input.clone();

        final int[] first = GrayscaleColorizer.tintWithOutline(
                input, 5, 5, 0xFFFF0000, 0xFF0000FF);
        final int[] second = GrayscaleColorizer.tintWithOutline(
                input, 5, 5, 0xFFFF0000, 0xFF0000FF);

        assertArrayEquals(copy, input);
        assertArrayEquals(first, second);
    }

    @Test
    void paletteNormalisesOrRejectsAlphaAndCanDriveTheOutlinePass() {
        final GrayscalePalette palette = GrayscalePalette.of(0x112233, 0xFF445566);
        assertEquals(0xFF112233, palette.primaryArgb());
        assertEquals(0xFF445566, palette.outlineArgb());

        assertThrows(IllegalArgumentException.class,
                () -> new GrayscalePalette(0x80112233, 0xFF000000));

        final int[] input = filledBlock5x5(0x80, 0);
        assertArrayEquals(
                GrayscaleColorizer.tintWithOutline(input, 5, 5,
                        palette.primaryArgb(), palette.outlineArgb(),
                        OutlineMask.Mode.FOUR_NEIGHBOURS, 0),
                GrayscaleColorizer.tintWithOutline(input, 5, 5, palette,
                        OutlineMask.Mode.FOUR_NEIGHBOURS));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GrayscaleColorizer.tintChannel(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> GrayscaleColorizer.tintChannel(0, 256));
        assertThrows(IllegalArgumentException.class,
                () -> GrayscaleColorizer.tintWithOutline(new int[6], 0xFFFFFFFF, 0xFF000000));
        assertThrows(IllegalArgumentException.class,
                () -> GrayscaleColorizer.tintWithOutline(new int[6], 2, 2, 0xFFFFFFFF, 0xFF000000));
        assertThrows(IllegalArgumentException.class,
                () -> OutlineMask.detect(new int[6], 2, 2));
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
