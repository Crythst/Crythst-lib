package io.mikaple.crythst.client.render.structure.selection;

import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import org.jetbrains.annotations.Nullable;

/**
 * Frame-local hand-off between outline extraction and outline submission.
 *
 * <p>{@link StructureSelectionOutline} publishes the vanilla outline render
 * state it installed together with the structure target that produced it. The
 * outline mixin then recognises its own state by identity and replaces the
 * default axis-aligned submission with an oriented one. Matching by identity
 * means a vanilla outline extracted for a real block is never touched, even
 * though the level render state object is reused between frames.</p>
 *
 * <p>Extraction and submission both run on the render thread, but the field is
 * volatile so a future renderer split cannot observe a torn publication.</p>
 */
final class StructureOutlineState {
    private record Pending(BlockOutlineRenderState outline, StructureOutlineTarget target) {
    }

    private static volatile Pending pending;

    private StructureOutlineState() {
    }

    /** Drops any previous frame's target. Called once per outline extraction. */
    static void clear() {
        pending = null;
    }

    static void publish(BlockOutlineRenderState outline, StructureOutlineTarget target) {
        pending = new Pending(outline, target);
    }

    /**
     * @return the target that produced {@code outline}, or {@code null} when
     *         this is a vanilla outline or a stale target
     */
    static @Nullable StructureOutlineTarget targetFor(@Nullable BlockOutlineRenderState outline) {
        final Pending current = pending;
        if (current == null || outline == null || current.outline() != outline) {
            return null;
        }
        // Kept until the next extraction: a frame can submit the level more
        // than once (for example through a portal camera), and every pass must
        // replace the default outline for this state instead of falling back to
        // the block-local shape at the published origin.
        return current.target();
    }
}
