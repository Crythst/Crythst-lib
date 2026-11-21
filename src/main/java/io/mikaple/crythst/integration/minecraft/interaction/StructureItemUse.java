package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.integration.minecraft.entity.placement.StructureEntityPlacement;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Shared item-use decision for physics-structure targets.
 *
 * <p>The client mixin and the server callbacks must agree on which items are
 * handled by the structure path. Keeping the predicate in one place avoids a
 * client that consumes a click while the server has no handler (or the
 * reverse).</p>
 */
public final class StructureItemUse {
    private StructureItemUse() {
    }

    /**
     * Whether using {@code stack} on a structure should go through the
     * structure-aware path instead of vanilla's real-block use.
     */
    public static boolean shouldHandleStructureUse(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return shouldHandleStructureUse(stack.getItem());
    }

    /**
     * Whether an item type is handled by the structure-aware use path. Kept as
     * a separate overload so the decision can be tested without constructing a
     * component-bound stack.
     */
    public static boolean shouldHandleStructureUse(@Nullable net.minecraft.world.item.Item item) {
        if (item == null) {
            return false;
        }
        return item instanceof BlockItem || StructureEntityPlacement.handles(item);
    }

    /**
     * Chooses the structure action for a right-click on a structure voxel.
     * Handled block/entity placement items keep {@link StructureAction#PLACE};
     * everything else (empty hand, tools, buckets, food, ...) uses the vanilla
     * block-interaction path through {@link StructureAction#USE}.
     *
     * <p>Keeping this decision here makes the client mixin and the server
     * callbacks agree on one routing rule and lets it be unit tested without a
     * running client.</p>
     */
    public static StructureAction actionForUse(@Nullable ItemStack stack) {
        return shouldHandleStructureUse(stack) ? StructureAction.PLACE : StructureAction.USE;
    }

    /**
     * Item-type overload used by tests and callers that already know the item.
     */
    public static StructureAction actionForUse(@Nullable net.minecraft.world.item.Item item) {
        return shouldHandleStructureUse(item) ? StructureAction.PLACE : StructureAction.USE;
    }
}
