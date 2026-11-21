package io.mikaple.crythst.client.texture.runtime;

import io.mikaple.crythst.client.texture.GrayscaleColorizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the runtime texture cache's key and identifier
 * determinism. These do not start Minecraft; the Minecraft-dependent upload
 * path is compile-checked only.
 */
class RuntimeTextureCacheTest {

    private static final String HUMAN_ARMOR =
            "crythst:textures/test/grayscale/humanoid_diamond.png";

    @Test
    void equalInputsProduceEqualKeysAndDynamicPaths() {
        RuntimeTextureKey first = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC, 0xFF114444);
        RuntimeTextureKey second = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC, 0xFF114444);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.dynamicPath(), second.dynamicPath());
    }

    @Test
    void rgbLiteralAndOpaqueArgbShareOneCacheEntry() {
        RuntimeTextureKey literal = RuntimeTextureKey.of(HUMAN_ARMOR, 0x44CCCC);
        RuntimeTextureKey opaque = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC);

        assertEquals(literal, opaque);
        assertEquals(literal.dynamicPath(), opaque.dynamicPath());
    }

    @Test
    void singleColourAndEqualOutlineShareOneCacheEntry() {
        RuntimeTextureKey single = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC);
        RuntimeTextureKey twoColour =
                RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC, 0xFF44CCCC);

        assertEquals(single, twoColour);
        assertEquals(single.dynamicPath(), twoColour.dynamicPath());
    }

    @Test
    void changingColoursChangesTheIdentifier() {
        RuntimeTextureKey cyan = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC);
        RuntimeTextureKey magenta = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFFFF44FF);

        assertNotEquals(cyan, magenta);
        assertNotEquals(cyan.dynamicPath(), magenta.dynamicPath());
    }

    @Test
    void changingSourceChangesTheIdentifier() {
        RuntimeTextureKey human = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC);
        RuntimeTextureKey leggings = RuntimeTextureKey.of(
                "crythst:textures/test/grayscale/humanoid_leggings_diamond.png", 0xFF44CCCC);

        assertNotEquals(human, leggings);
        assertNotEquals(human.dynamicPath(), leggings.dynamicPath());
    }

    @Test
    void dynamicPathIsAValidStableIdentifierPath() {
        String path = RuntimeTextureKey.of(HUMAN_ARMOR, 0xFF44CCCC).dynamicPath();

        assertTrue(path.startsWith(RuntimeTextureKey.DYNAMIC_PATH_PREFIX));
        assertTrue(path.matches("[a-z0-9_./-]+"), "must be a valid Identifier path: " + path);
        assertEquals(64, path.length() - RuntimeTextureKey.DYNAMIC_PATH_PREFIX.length(),
                "SHA-256 is rendered as 64 lowercase hex characters");
    }

    @Test
    void rejectsPartiallyTransparentPaletteColours() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeTextureKey.of(HUMAN_ARMOR, 0x8044CCCC));
    }

    @Test
    void colorizerTintsTheMaskAndPreservesAlpha() {
        // Black mask -> black, mid mask -> near the base colour, white -> white.
        int[] source = {0xFF000000, 0xFF808080, 0xFFFFFFFF, 0x00000000};

        int[] tinted = GrayscaleColorizer.tint(source, 0xFF44CCCC);

        assertEquals(0xFF000000, tinted[0]);
        assertEquals(0xFF44CCCC, tinted[1]);
        assertEquals(0xFFFFFFFF, tinted[2]);
        assertEquals(0x00000000, tinted[3], "transparent pixels keep source alpha");
        // The helper never mutates its input.
        assertEquals(0xFF808080, source[1]);
    }

    @Test
    void outlineVariantUsesTheSecondColourOnCoveredEdges() {
        // 3x3 fully covered: the centre is the only pixel whose four orthogonal
        // neighbours are all covered; every border pixel touches empty space.
        int[] source = new int[9];
        java.util.Arrays.fill(source, 0xFF808080);

        int[] tinted = GrayscaleColorizer.tintWithOutline(
                source, 3, 3, 0xFF44CCCC, 0xFFFF00FF);

        final int primary = GrayscaleColorizer.tintArgb(0xFF808080, 0xFF44CCCC);
        final int outline = GrayscaleColorizer.tintArgb(0xFF808080, 0xFFFF00FF);
        for (int i = 0; i < source.length; i++) {
            assertEquals(i == 4 ? primary : outline, tinted[i],
                    "unexpected colour at index " + i);
        }
    }
}
