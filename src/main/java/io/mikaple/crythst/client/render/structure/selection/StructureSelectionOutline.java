package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes the structure voxel selected by
 * {@link ClientStructureSelection} through Minecraft's vanilla block-outline
 * render state.
 *
 * <p>Vanilla's {@code LevelExtractor} derives the outline from
 * {@code Minecraft.hitResult} and the real block at that position. Structure
 * voxels are not real blocks, so the extractor produces no usable outline for
 * them. This handler runs after extraction and installs an outline state
 * carrying the voxel's block-local visual shape. The outline mixin recognises
 * that state and submits it with the structure voxel's real pose (rotation and
 * {@code blockScale}) through the vanilla {@code submitHitOutline} path.</p>
 *
 * <p>The block-local shape is used unchanged, so stairs, slabs and every other
 * block-state shape keep their exact geometry; the pose supplies orientation
 * and size. Real blocks are never touched because the handler only runs while
 * the current vanilla hit is a {@link StructureBlockHitResult}, which the
 * selection layer only returns when the structure won the crosshair
 * comparison.</p>
 */
public final class StructureSelectionOutline {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureSelectionOutline() {
    }

    /**
     * Registers the vanilla outline override. Safe to call more than once.
     */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register(
                    StructureSelectionOutline::afterBlockOutlineExtraction);
        }
    }

    /**
     * Returns the target that produced this frame's published structure
     * outline, or {@code null} for real-block outlines and stale state.
     *
     * <p>Called by the outline mixin during submission. The target stays
     * published until the next extraction so repeated level submissions in one
     * frame (for example portal passes) each render the oriented outline.</p>
     */
    public static StructureOutlineTarget publishedTargetFor(BlockOutlineRenderState outline) {
        return StructureOutlineState.targetFor(outline);
    }

    private static void afterBlockOutlineExtraction(LevelExtractionContext context,
                                                     net.minecraft.world.phys.HitResult result) {
        // Drop the previous frame's hand-off first: if this frame has no
        // structure target, the vanilla outline (or no outline) stays in charge.
        StructureOutlineState.clear();

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !CrythstFabric.config().structureBlockInteractionEnabled) {
            return;
        }
        final StructureBlockHitResult hit = ClientStructureSelection.current(minecraft);
        if (hit == null) {
            // A real block, entity or miss owns the outline.
            return;
        }

        final StructureOutlineTarget target = StructureOutlineTarget.from(hit);
        final VoxelShape shape = StructureOutlineShape.localShape(target,
                context.level(), CollisionContext.of(minecraft.player));
        final boolean highContrast = minecraft.options.highContrastBlockOutline().get();
        final BlockOutlineRenderState outline = new BlockOutlineRenderState(
                BlockPos.ZERO, isTranslucent(hit.blockState()), highContrast, shape);

        context.levelState().blockOutlineRenderState = outline;
        StructureOutlineState.publish(outline, target);
    }

    private static boolean isTranslucent(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        try {
            return Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                    .get(state).hasMaterialFlag(1);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
