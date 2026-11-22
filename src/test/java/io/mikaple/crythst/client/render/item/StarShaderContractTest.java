package io.mikaple.crythst.client.render.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Java/shader contract that is not compile-checked by the JVM:
 * the fragment shader must keep the overlay-only discard, the same alpha
 * cutoff, and the OIT-safe lightmap access.
 */
class StarShaderContractTest {

    private static String shader(String name) throws IOException {
        String path = "/assets/crythst/shaders/core/" + name;
        try (InputStream input = StarShaderContractTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fragmentShaderOverlayOnlyDiscardsNonMaskPixels() throws IOException {
        String fragment = shader("star.fsh");
        assertTrue(fragment.contains("#ifdef STAR_MASK_ONLY"));
        assertTrue(fragment.contains("if (!isMask)"));
        assertTrue(fragment.contains("discard;"));
        assertTrue(fragment.contains("color.a < 0.05"),
                "the silhouette alpha cutoff must match StarMaskMath.ALPHA_CUTOFF");
        assertTrue(fragment.contains("c.a > 0.5"),
                "effect-mask alpha must match StarMaskMath.EFFECT_MASK_MIN_ALPHA");
        assertTrue(fragment.contains("MASK_TOLERANCE = 0.1"),
                "mask tolerance must match StarMaskMath.EFFECT_MASK_TOLERANCE");
    }

    @Test
    void fragmentShaderIsOitSafe() throws IOException {
        String fragment = shader("star.fsh");
        assertTrue(fragment.contains("#ifndef OIT_ALPHA_ONLY"));
        assertTrue(fragment.contains("#else"));
        assertTrue(fragment.contains("executeAlphaOnlyPhase(gl_FragCoord.z, color.a);"));
        assertTrue(fragment.contains("sampleColorForAccumulation"),
                "translucent star quads must support the OIT accumulate stage");
    }
}
