package io.mikaple.crythst.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code LevelRenderer}'s private outline submission so the structure
 * outline can reuse vanilla render types, colours, width and debug-shape
 * handling instead of duplicating them.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererOutlineInvoker extends StructureOutlineRenderer.HitOutlineSubmitter {
    @Override
    @Invoker("submitHitOutline")
    void crythst$submitHitOutline(PoseStack poseStack,
                                  SubmitNodeCollector submitNodeCollector,
                                  RenderType renderType,
                                  BlockOutlineRenderState state,
                                  int color,
                                  float width,
                                  boolean afterTerrain);
}
