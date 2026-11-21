package io.mikaple.crythst.client.render.block;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.Crythst;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Example {@link SpecialBlockRenderer} that renders a block as its own item
 * model.
 *
 * <p>This is the 26.3 equivalent of EnderTech's Enderite block renderer, but it
 * does not use a block entity and never calls
 * {@code BlockEntityRenderer#submit}. The block's item stack is resolved with
 * {@link ItemModelResolver#updateForNonLiving} using
 * {@link ItemDisplayContext#NONE}, then submitted through the level's
 * {@link SubmitNodeCollector}. Because the item-model pipeline is used, a
 * custom item special model (including a star-shader model) participates
 * automatically: point the block item's item model at the special model and
 * this renderer will draw it in the world too.</p>
 *
 * <p>The item model is resolved again every frame, matching vanilla item frame
 * behaviour and keeping the result fresh after a resource reload. The
 * {@link ItemStack} itself is created lazily on the render thread: creating it
 * in a static initializer during early resource loading can fail with
 * "Components not bound yet".</p>
 */
public final class ItemModelBlockRenderer implements SpecialBlockRenderer {
    private final Block block;
    /** Shared per-renderer state; re-resolved each frame, like an item frame. */
    private final ItemStackRenderState renderState = new ItemStackRenderState();

    private ItemStack stack;
    private boolean stackResolved;

    public ItemModelBlockRenderer(Block block) {
        this.block = block;
    }

    /**
     * Convenience factory that also registers this renderer.
     */
    public static ItemModelBlockRenderer register(Block block) {
        final ItemModelBlockRenderer renderer = new ItemModelBlockRenderer(block);
        SpecialBlockRenderers.register(block, renderer);
        return renderer;
    }

    @Override
    public void submit(SpecialBlockRenderContext context, PoseStack poseStack,
                       SubmitNodeCollector collector, int lightCoords, int overlayCoords) {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        if (stack == null && !resolveStack()) {
            return;
        }

        final ItemModelResolver resolver = minecraft.getItemModelResolver();
        resolver.updateForNonLiving(renderState, stack, ItemDisplayContext.NONE, player);
        if (renderState.isEmpty()) {
            return;
        }

        // The item path centres its model around the origin (the -0.5 shift used
        // for dropped/handheld items). The manager's pose is already at the
        // block corner, so +0.5 puts the cube back into the block cell.
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        renderState.submit(poseStack, collector, lightCoords, overlayCoords, 0);
        poseStack.popPose();
    }

    /**
     * @return the block whose item model is rendered
     */
    public Block block() {
        return block;
    }

    private boolean resolveStack() {
        if (stackResolved) {
            // block.asItem() does not change between resource reloads, so a
            // missing item is permanent and should not be retried every frame.
            return false;
        }
        stackResolved = true;

        final ItemStack candidate = new ItemStack(block);
        if (candidate.isEmpty()) {
            Crythst.LOGGER.warn("Special block renderer for {} has no item form; "
                    + "ItemModelBlockRenderer will not draw it", block);
            return false;
        }

        stack = candidate;
        return true;
    }
}
