package io.mikaple.crythst.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Swaps the shared {@link SkyRenderer} for a portal-target-bound instance during
 * a capture. SkyRenderer caches the target passed to its constructor, so using
 * the main instance would draw the destination sky into the main target.
 */
@Mixin(LevelRenderer.class)
public interface PortalLevelRendererAccessor {
    @Accessor("skyRenderer")
    SkyRenderer crythst$getSkyRenderer();

    @Accessor("skyRenderer")
    void crythst$setSkyRenderer(SkyRenderer renderer);
}
