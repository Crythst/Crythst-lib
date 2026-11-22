package io.mikaple.crythst.client.render.item;

/**
 * Pure math shared by the star mask shader and its tests.
 *
 * <p>The star effect is a second pass over the exact geometry of the real
 * item/block model. That pass uses two masks:</p>
 * <ul>
 *     <li><b>silhouette mask</b> - the real model's depth buffer. The star pass
 *     depth-tests {@link StarRenderPlan#STAR_DEPTH_TEST} against it and never
 *     writes depth itself, so it can only decorate pixels where the real model
 *     is the front-most surface.</li>
 *     <li><b>effect mask</b> - opaque near-black texels inside the model's
 *     texture. Only those pixels receive the procedural starfield and recessed
 *     purple walls; every other texel is discarded.</li>
 * </ul>
 *
 * <p>This class mirrors the corresponding constants and coordinate transforms
 * in {@code assets/crythst/shaders/core/star.vsh} / {@code star.fsh}. Keeping
 * them in Java makes the edge cases testable without a GPU or a client
 * bootstrap.</p>
 */
public final class StarMaskMath {

    /** Fragment shader cutoff: a texel below this alpha is not part of the model. */
    public static final float ALPHA_CUTOFF = 0.05F;

    /** Effect-mask texels must be more opaque than this. */
    public static final float EFFECT_MASK_MIN_ALPHA = 0.5F;

    /** Maximum per-channel distance from pure black that still counts as mask. */
    public static final float EFFECT_MASK_TOLERANCE = 0.1F;

    /** The star pass always replaces a mask texel with a fully opaque pixel. */
    public static final float STAR_PIXEL_OPACITY = 1.0F;

    private StarMaskMath() {
    }

    /** Mirrors {@code if (color.a < 0.05) discard;} in {@code star.fsh}. */
    public static boolean isVisible(float alpha) {
        return alpha >= ALPHA_CUTOFF;
    }

    /**
     * Mirrors {@code isMaskTexel}: near-black RGB with enough alpha to be part
     * of the model. Transparent black ({@code 0,0,0,0}) is explicitly excluded.
     */
    public static boolean isEffectMask(float red, float green, float blue, float alpha) {
        return alpha > EFFECT_MASK_MIN_ALPHA
                && Math.abs(red) < EFFECT_MASK_TOLERANCE
                && Math.abs(green) < EFFECT_MASK_TOLERANCE
                && Math.abs(blue) < EFFECT_MASK_TOLERANCE;
    }

    /** Mirrors the alpha written for effect-mask pixels. */
    public static float effectPixelOpacity(boolean effectMask) {
        return effectMask ? STAR_PIXEL_OPACITY : 0.0F;
    }

    /**
     * Converts a screen pixel (origin bottom-left, like {@code gl_FragCoord})
     * to normalized device coordinates.
     */
    public static float[] screenToNdc(float pixelX, float pixelY, float screenWidth, float screenHeight) {
        if (!(screenWidth > 0.0F) || !(screenHeight > 0.0F)) {
            throw new IllegalArgumentException("screen size must be positive");
        }
        return new float[]{
                pixelX / screenWidth * 2.0F - 1.0F,
                pixelY / screenHeight * 2.0F - 1.0F
        };
    }

    /**
     * The starfield is generated in screen space so the stars stay fixed while
     * the item moves. X is aspect-corrected so stars stay square.
     */
    public static float[] starSpaceUv(float ndcX, float ndcY, float screenWidth, float screenHeight) {
        if (!(screenHeight > 0.0F)) {
            throw new IllegalArgumentException("screen height must be positive");
        }
        return new float[]{ndcX * (screenWidth / screenHeight), ndcY};
    }

    /**
     * Projects a point on the recessed floor back onto the item texture.
     *
     * <p>{@code star.fsh} computes the screen-space displacement
     * {@code deltaPx} between the current pixel and the recessed floor sample,
     * then walks along the texture derivatives. This helper exposes the same
     * affine mapping for unit tests.</p>
     *
     * @param u          current texture U
     * @param v          current texture V
     * @param duDx       d(U)/d(screen x)
     * @param dvDx       d(V)/d(screen x)
     * @param duDy       d(U)/d(screen y)
     * @param dvDy       d(V)/d(screen y)
     * @param deltaPxX   displacement in screen pixels along x
     * @param deltaPxY   displacement in screen pixels along y
     */
    public static float[] projectMaskUv(float u, float v,
                                        float duDx, float dvDx,
                                        float duDy, float dvDy,
                                        float deltaPxX, float deltaPxY) {
        return new float[]{
                u + duDx * deltaPxX + duDy * deltaPxY,
                v + dvDx * deltaPxX + dvDy * deltaPxY
        };
    }

    /**
     * Continuous "is this texel a mask floor?" weight used for wall normals.
     * Mirrors {@code floorWeight} in {@code star.fsh}: alpha ramps from 0 to 1
     * and RGB distance from black ramps from 1 to 0.
     */
    public static float floorWeight(float red, float green, float blue, float alpha) {
        float distance = (float) Math.sqrt(red * red + green * green + blue * blue);
        float t = clamp01(distance / EFFECT_MASK_TOLERANCE);
        // smoothstep(0, 1, t)
        float smooth = t * t * (3.0F - 2.0F * t);
        return clamp01(alpha) * (1.0F - smooth);
    }

    /** Mirrors {@code isFloorTexel}: opaque texels only. */
    public static boolean isFloorTexel(float red, float green, float blue, float alpha) {
        return alpha > EFFECT_MASK_MIN_ALPHA
                && Math.abs(red) < EFFECT_MASK_TOLERANCE
                && Math.abs(green) < EFFECT_MASK_TOLERANCE
                && Math.abs(blue) < EFFECT_MASK_TOLERANCE;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
