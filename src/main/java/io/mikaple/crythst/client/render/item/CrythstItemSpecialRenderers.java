package io.mikaple.crythst.client.render.item;

import io.mikaple.crythst.Crythst;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;

/**
 * Client-side registration for Crythst's item special renderers.
 *
 * <p>This class is client-only: it touches
 * {@link SpecialModelRenderers} and the model/render pipeline, so it must never
 * be loaded on a dedicated server. Call {@link #register()} once during client
 * initialization, for example from a mod's {@code ClientModInitializer}:</p>
 *
 * <pre>{@code
 * CrythstItemSpecialRenderers.register();
 * }</pre>
 *
 * <p>Registration is idempotent, so a mod may call it more than once (or two
 * mods may both call it) without producing duplicate mappings.</p>
 */
public final class CrythstItemSpecialRenderers {

    /**
     * Special-model type id: {@code crythst:star}.
     *
     * <p>This is the value an item model JSON passes to the
     * {@code minecraft:special} wrapper's {@code type} field.</p>
     */
    public static final Identifier STAR_ID = Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "star");

    /**
     * Shader id: {@code crythst:core/star}, resolving to
     * {@code assets/crythst/shaders/core/star.vsh} and {@code .fsh}.
     */
    public static final Identifier STAR_SHADER = Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/star");

    /** Render pipeline location: {@code crythst:star_item}. */
    public static final Identifier STAR_PIPELINE = Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "star_item");

    /** Unique {@code RenderType} name used by the star effect. */
    public static final String STAR_RENDER_TYPE_NAME = "crythst_star";

    private static boolean registered;

    private CrythstItemSpecialRenderers() {
    }

    /**
     * Registers {@link StarItemModelRenderer.Unbaked#MAP_CODEC} under
     * {@link #STAR_ID}.
     *
     * <p>Safe to call repeatedly; only the first call installs the mapping.</p>
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        SpecialModelRenderers.ID_MAPPER.put(STAR_ID, StarItemModelRenderer.Unbaked.MAP_CODEC);
        registered = true;
    }
}
