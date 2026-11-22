package io.mikaple.crythst.client.render.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarMaskMathTest {

    private static final float EPSILON = 1.0e-5F;

    @Test
    void alphaCutoffMatchesShaderEdgeInclusive() {
        assertFalse(StarMaskMath.isVisible(0.0F));
        assertFalse(StarMaskMath.isVisible(0.049F));
        assertTrue(StarMaskMath.isVisible(StarMaskMath.ALPHA_CUTOFF));
        assertTrue(StarMaskMath.isVisible(0.1F));
        assertTrue(StarMaskMath.isVisible(1.0F));
    }

    @Test
    void effectMaskRequiresOpaqueNearBlackAndRejectsTransparentBlack() {
        assertTrue(StarMaskMath.isEffectMask(0.0F, 0.0F, 0.0F, 1.0F));
        assertTrue(StarMaskMath.isEffectMask(0.09F, 0.0F, 0.05F, 0.6F));
        assertFalse(StarMaskMath.isEffectMask(0.0F, 0.0F, 0.0F, 0.0F),
                "transparent black is not an effect mask");
        assertFalse(StarMaskMath.isEffectMask(0.0F, 0.0F, 0.0F, StarMaskMath.EFFECT_MASK_MIN_ALPHA),
                "alpha must be strictly greater than the mask alpha threshold");
        assertFalse(StarMaskMath.isEffectMask(0.11F, 0.0F, 0.0F, 1.0F));
        assertFalse(StarMaskMath.isEffectMask(0.0F, 0.0F, 1.0F, 1.0F));
    }

    @Test
    void effectPixelsAreAlwaysFullyOpaque() {
        assertEquals(1.0F, StarMaskMath.effectPixelOpacity(true));
        assertEquals(0.0F, StarMaskMath.effectPixelOpacity(false));
    }

    @Test
    void screenPixelsConvertToNdcOnBothAxes() {
        assertArrayEqualsWithEpsilon(new float[]{-1.0F, -1.0F},
                StarMaskMath.screenToNdc(0.0F, 0.0F, 1920.0F, 1080.0F));
        assertArrayEqualsWithEpsilon(new float[]{1.0F, 1.0F},
                StarMaskMath.screenToNdc(1920.0F, 1080.0F, 1920.0F, 1080.0F));
        assertArrayEqualsWithEpsilon(new float[]{0.0F, 0.0F},
                StarMaskMath.screenToNdc(960.0F, 540.0F, 1920.0F, 1080.0F));
        assertThrows(IllegalArgumentException.class,
                () -> StarMaskMath.screenToNdc(0.0F, 0.0F, 0.0F, 1080.0F));
    }

    @Test
    void starfieldUvIsAspectCorrectedButNotShifted() {
        assertArrayEqualsWithEpsilon(new float[]{0.5F * (1920.0F / 1080.0F), 0.5F},
                StarMaskMath.starSpaceUv(0.5F, 0.5F, 1920.0F, 1080.0F));
        assertArrayEqualsWithEpsilon(new float[]{0.0F, 0.0F},
                StarMaskMath.starSpaceUv(0.0F, 0.0F, 1920.0F, 1080.0F));
    }

    @Test
    void floorUvProjectionWalksAlongTextureDerivatives() {
        assertArrayEqualsWithEpsilon(new float[]{0.25F, 0.75F},
                StarMaskMath.projectMaskUv(0.25F, 0.75F,
                        0.01F, 0.02F, 0.03F, 0.04F, 0.0F, 0.0F));

        // One screen pixel along +x plus two along +y.
        float expectedU = 0.25F + 0.01F + 2.0F * 0.03F;
        float expectedV = 0.75F + 0.02F + 2.0F * 0.04F;
        assertArrayEqualsWithEpsilon(new float[]{expectedU, expectedV},
                StarMaskMath.projectMaskUv(0.25F, 0.75F,
                        0.01F, 0.02F, 0.03F, 0.04F, 1.0F, 2.0F));
    }

    @Test
    void floorWeightRampsFromSolidMaskToNonMask() {
        assertEquals(1.0F, StarMaskMath.floorWeight(0.0F, 0.0F, 0.0F, 1.0F), EPSILON);
        assertEquals(0.0F, StarMaskMath.floorWeight(0.0F, 0.0F, 0.0F, 0.0F), EPSILON);
        assertEquals(0.0F, StarMaskMath.floorWeight(1.0F, 1.0F, 1.0F, 1.0F), EPSILON);
        float halfway = StarMaskMath.floorWeight(0.05F, 0.0F, 0.0F, 1.0F);
        assertTrue(halfway > 0.0F && halfway < 1.0F);
    }

    @Test
    void floorTexelExcludesTransparentBlack() {
        assertTrue(StarMaskMath.isFloorTexel(0.0F, 0.0F, 0.0F, 1.0F));
        assertFalse(StarMaskMath.isFloorTexel(0.0F, 0.0F, 0.0F, 0.0F));
        assertFalse(StarMaskMath.isFloorTexel(0.0F, 0.5F, 0.0F, 1.0F));
    }

    private static void assertArrayEqualsWithEpsilon(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], EPSILON, "index " + i);
        }
    }
}
