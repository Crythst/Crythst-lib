package io.mikaple.crythst.client.render.item;

import java.util.List;

/**
 * Pure description of the two-pass star pipeline.
 *
 * <p>Every star model is submitted twice with identical geometry and pose:</p>
 * <ol>
 *     <li><b>real pass</b> - the baked model with its original item render
 *     types. Cutout and translucent materials, glint, tinting, lightmap and
 *     overlay behaviour are exactly vanilla. This pass writes depth.</li>
 *     <li><b>star pass</b> - the same quads with the star shader. It runs in
 *     the same render phase as the real pass (solid for cutout, translucent
 *     for translucent quads), depth-tests against the depth the real pass
 *     wrote, and does <em>not</em> write depth. The shader discards every
 *     non-mask texel, so this pass only replaces the model's opaque black
 *     "window" pixels.</li>
 * </ol>
 *
 * <p>RenderPearl does not expose hardware stencil operations, so the
 * silhouette mask is the real model's depth buffer plus the shared geometry.
 * This is the closest equivalent to a stencil "equal" decal pass and avoids
 * the old single-pass design where the star shader also owned the item's
 * depth/colour state.</p>
 */
public final class StarRenderPlan {

    /** Render phase used by {@code SubmitNodeCollection#submitItem}. */
    public enum Phase {
        SOLID,
        TRANSLUCENT
    }

    /** RenderPearl uses reversed-Z depth, so "not behind" is {@code >=}. */
    public enum DepthTest {
        GREATER_THAN_OR_EQUAL
    }

    /** The star pass must never write depth; the real model owns the depth buffer. */
    public static final boolean STAR_WRITES_DEPTH = false;

    /** Depth comparison used by the star pass. */
    public static final DepthTest STAR_DEPTH_TEST = DepthTest.GREATER_THAN_OR_EQUAL;

    public record Pass(Phase phase, boolean blended, boolean writesDepth, DepthTest depthTest) {
    }

    private StarRenderPlan() {
    }

    /** The normal model pass. Depth write is preserved from the vanilla render type. */
    public static Pass real(boolean realQuadsBlended) {
        return new Pass(
                realQuadsBlended ? Phase.TRANSLUCENT : Phase.SOLID,
                realQuadsBlended,
                true,
                DepthTest.GREATER_THAN_OR_EQUAL
        );
    }

    /**
     * The star overlay pass. It deliberately matches the real pass's phase so
     * it is submitted after the real model in both the solid and the
     * translucent feature lists.
     */
    public static Pass star(boolean realQuadsBlended) {
        return new Pass(
                realQuadsBlended ? Phase.TRANSLUCENT : Phase.SOLID,
                realQuadsBlended,
                STAR_WRITES_DEPTH,
                STAR_DEPTH_TEST
        );
    }

    /** Real pass first, then the star overlay in the same phase. */
    public static List<Pass> passes(boolean realQuadsBlended) {
        return List.of(real(realQuadsBlended), star(realQuadsBlended));
    }

    /** True when the star pass is guaranteed to run after the real pass. */
    public static boolean starFollowsReal(boolean realQuadsBlended) {
        Pass real = real(realQuadsBlended);
        Pass star = star(realQuadsBlended);
        return real.phase() == star.phase() && !star.writesDepth();
    }
}
