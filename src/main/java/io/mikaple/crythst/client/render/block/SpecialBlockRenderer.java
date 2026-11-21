package io.mikaple.crythst.client.render.block;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Draws one block that has opted into special rendering.
 *
 * <p>This is deliberately <em>not</em> a {@code BlockEntityRenderer}: the
 * renderer is never attached to a block entity and is never invoked through
 * {@code BlockEntityRenderer#submit}. Instead,
 * {@link SpecialBlockRenderManager} finds registered blocks on the client and
 * calls this interface once per visible block from the level render event.</p>
 *
 * <p>The supplied {@link PoseStack} has already been translated so that the
 * origin is the minimum corner of the block, relative to the render camera.
 * The callback must therefore translate by {@code +0.5} to reach the block
 * centre, exactly like a block-entity renderer whose pose is at the block
 * corner. The callback owns the pushed pose for its own model, but must not
 * leak a transform: push before translating and pop afterwards.</p>
 *
 * <p>Implementations are called on the render thread. They may read immutable
 * data from {@link SpecialBlockRenderContext}, but must not modify the world or
 * the cached block list.</p>
 */
@FunctionalInterface
public interface SpecialBlockRenderer {
    /**
     * @param context        immutable per-block render data
     * @param poseStack      pose already translated to the block's minimum corner
     * @param collector      the level's submit-node collector
     * @param lightCoords    packed block/sky light for this block
     * @param overlayCoords  overlay texture coordinates, usually
     *                       {@code OverlayTexture.NO_OVERLAY}
     */
    void submit(SpecialBlockRenderContext context, PoseStack poseStack,
                SubmitNodeCollector collector, int lightCoords, int overlayCoords);
}
