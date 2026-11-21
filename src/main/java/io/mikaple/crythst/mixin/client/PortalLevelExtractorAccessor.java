package io.mikaple.crythst.mixin.client;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of the extractor's pending one-shot reset flags.
 *
 * <p>A nested extraction for the portal camera would otherwise consume
 * {@code shouldResetLevelRenderData} / {@code shouldResetSkyRenderer} /
 * {@code shouldResetChunkLayerSampler} before the main camera's extraction
 * runs. These resets are global, not camera-specific, so the portal capture
 * skips that frame and lets the normal extraction perform them.</p>
 */
@Mixin(LevelExtractor.class)
public interface PortalLevelExtractorAccessor {
    @Accessor("shouldResetLevelRenderData")
    boolean crythst$shouldResetLevelRenderData();

    @Accessor("shouldInvalidateCompiledGeometry")
    boolean crythst$shouldInvalidateCompiledGeometry();

    @Accessor("shouldResetSkyRenderer")
    boolean crythst$shouldResetSkyRenderer();

    @Accessor("shouldResetChunkLayerSampler")
    boolean crythst$shouldResetChunkLayerSampler();
}
