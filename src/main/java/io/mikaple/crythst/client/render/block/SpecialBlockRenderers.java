package io.mikaple.crythst.client.render.block;

import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry mapping a {@link Block} to its special renderer.
 *
 * <p>Registration is expected during client initialisation. The registry is
 * only consulted by {@link SpecialBlockRenderManager}'s client-tick scanner;
 * adding a renderer after the first scan simply takes effect on the next scan.
 * The map is concurrent so a registration cannot corrupt an in-flight render
 * iteration.</p>
 */
public final class SpecialBlockRenderers {
    private static final Map<Block, SpecialBlockRenderer> RENDERERS = new ConcurrentHashMap<>();

    private SpecialBlockRenderers() {
    }

    /**
     * Registers (or replaces) the renderer for {@code block}.
     *
     * @return the previously registered renderer, or {@code null}
     */
    public static SpecialBlockRenderer register(Block block, SpecialBlockRenderer renderer) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(renderer, "renderer");
        return RENDERERS.put(block, renderer);
    }

    /**
     * @return the renderer for {@code block}, or {@code null} when the block is
     *         not registered
     */
    public static SpecialBlockRenderer get(Block block) {
        return RENDERERS.get(block);
    }

    public static boolean isRegistered(Block block) {
        return RENDERERS.containsKey(block);
    }

    public static boolean isEmpty() {
        return RENDERERS.isEmpty();
    }

    public static int size() {
        return RENDERERS.size();
    }
}
