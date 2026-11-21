package io.mikaple.crythst.client.texture.runtime;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;

/**
 * Entity-side facade over {@link RuntimeTextureCache}.
 *
 * <p>Entity renderers in 26.3 no longer draw immediately: they extract a
 * render state and submit models through {@code SubmitNodeCollector}. The
 * texture swap therefore happens where the render type is chosen, not inside a
 * {@code bindTexture} call. This class provides the two pieces such a call site
 * needs:</p>
 *
 * <ol>
 *     <li>{@link #textureFor} returns a generated {@link Identifier} that can be
 *     passed to {@code RenderTypes.*} or to {@code TextureManager};</li>
 *     <li>{@link #armor}, {@link #entityCutout}, {@link #entitySolid}, etc.
 *     build the matching vanilla render type over that identifier.</li>
 * </ol>
 *
 * <p>All methods that create textures must be called on the client/render
 * thread. {@link #textureForAsync} submits work from other threads. Clearing is
 * exposed through {@link #clear()} and {@link #releaseAll()}.</p>
 *
 * <p>See {@code docs/runtime-entity-textures.md} for the diamond-armor
 * integration point and for an opt-in example mixin.</p>
 */
public final class EntityGrayscaleTextures {

    private static final RuntimeTextureCache CACHE = new RuntimeTextureCache();

    private EntityGrayscaleTextures() {
    }

    /** The process-wide cache used by the convenience methods. */
    public static RuntimeTextureCache cache() {
        return CACHE;
    }

    /**
     * Returns the generated texture identifier for a grayscale source and one
     * colour. Must be called on the client/render thread.
     */
    public static Identifier textureFor(Identifier grayscaleSource, int color) {
        return CACHE.get(grayscaleSource, color);
    }

    /**
     * Returns the generated texture identifier for a grayscale source with a
     * primary fill and an outline colour. Must be called on the client/render
     * thread.
     */
    public static Identifier textureFor(Identifier grayscaleSource,
                                        int primaryColor,
                                        int outlineColor) {
        return CACHE.get(grayscaleSource, primaryColor, outlineColor);
    }

    /**
     * Asynchronous variant of {@link #textureFor}; safe from worker threads.
     */
    public static CompletableFuture<Identifier> textureForAsync(Identifier grayscaleSource,
                                                                int color) {
        return CACHE.getAsync(grayscaleSource, color);
    }

    /**
     * Asynchronous two-colour variant.
     */
    public static CompletableFuture<Identifier> textureForAsync(Identifier grayscaleSource,
                                                                int primaryColor,
                                                                int outlineColor) {
        return CACHE.getAsync(grayscaleSource, primaryColor, outlineColor);
    }

    // ------------------------------------------------------------------
    // Render types
    // ------------------------------------------------------------------

    /**
     * The standard armor render type, backed by a generated texture.
     *
     * <p>This is the correct render type for humanoid armor layers and matches
     * vanilla's {@code RenderTypes.armorCutoutNoCull}. The render type factory is
     * memoised by vanilla, so calling this repeatedly is cheap.</p>
     */
    public static RenderType armor(Identifier grayscaleSource, int color) {
        return RenderTypes.armorCutoutNoCull(textureFor(grayscaleSource, color));
    }

    /**
     * Two-colour armor render type.
     */
    public static RenderType armor(Identifier grayscaleSource,
                                   int primaryColor,
                                   int outlineColor) {
        return RenderTypes.armorCutoutNoCull(
                textureFor(grayscaleSource, primaryColor, outlineColor));
    }

    /**
     * Armor render type including the vanilla glint pass.
     */
    public static RenderType armorGlint(Identifier grayscaleSource, int color) {
        return RenderTypes.armorCutoutNoCullGlint(textureFor(grayscaleSource, color));
    }

    /**
     * Standard entity cutout, matching vanilla mob textures. Vanilla's
     * {@code entityCutout} uses no-cull state; use {@link #entityCutoutCull} if
     * back-face culling is wanted.
     */
    public static RenderType entityCutout(Identifier grayscaleSource, int color) {
        return RenderTypes.entityCutout(textureFor(grayscaleSource, color));
    }

    /**
     * Two-colour standard entity cutout.
     */
    public static RenderType entityCutout(Identifier grayscaleSource,
                                          int primaryColor,
                                          int outlineColor) {
        return RenderTypes.entityCutout(
                textureFor(grayscaleSource, primaryColor, outlineColor));
    }

    /**
     * Entity cutout with culling disabled. Vanilla's default
     * {@code entityCutout} already uses no-cull state, so this is an alias for
     * call sites that spell out the intent.
     */
    public static RenderType entityCutoutNoCull(Identifier grayscaleSource, int color) {
        return RenderTypes.entityCutout(textureFor(grayscaleSource, color));
    }

    /**
     * Entity cutout with back-face culling enabled.
     */
    public static RenderType entityCutoutCull(Identifier grayscaleSource, int color) {
        return RenderTypes.entityCutoutCull(textureFor(grayscaleSource, color));
    }

    /**
     * Opaque entity render type, matching vanilla {@code entitySolid}.
     */
    public static RenderType entitySolid(Identifier grayscaleSource, int color) {
        return RenderTypes.entitySolid(textureFor(grayscaleSource, color));
    }

    /**
     * Translucent entity render type.
     */
    public static RenderType entityTranslucent(Identifier grayscaleSource, int color) {
        return RenderTypes.entityTranslucent(textureFor(grayscaleSource, color));
    }

    /**
     * Emissive translucent entity render type (eyes, glow layers).
     */
    public static RenderType entityTranslucentEmissive(Identifier grayscaleSource, int color) {
        return RenderTypes.entityTranslucentEmissive(textureFor(grayscaleSource, color));
    }

    /**
     * Item cutout render type for a generated texture. Intended for custom
     * special-model renderers; ordinary item models still go through the item
     * atlas and cannot be pointed at a standalone texture by model JSON alone.
     */
    public static RenderType itemCutout(Identifier grayscaleSource, int color) {
        return RenderTypes.itemCutout(textureFor(grayscaleSource, color));
    }

    /**
     * Item translucent render type for a generated texture.
     */
    public static RenderType itemTranslucent(Identifier grayscaleSource, int color) {
        return RenderTypes.itemTranslucent(textureFor(grayscaleSource, color));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Schedules release of every generated texture on the client thread.
     */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Releases every generated texture synchronously. Must be called on the
     * client/render thread.
     */
    public static void releaseAll() {
        CACHE.releaseAll();
    }

    /**
     * Releases one generated texture, if present.
     */
    public static boolean release(Identifier grayscaleSource, int primaryColor, int outlineColor) {
        return CACHE.release(grayscaleSource, primaryColor, outlineColor);
    }

    /**
     * Releases one single-colour generated texture, if present.
     */
    public static boolean release(Identifier grayscaleSource, int color) {
        return CACHE.release(grayscaleSource, color);
    }

    /**
     * Number of currently registered generated textures.
     */
    public static int size() {
        return CACHE.size();
    }
}
