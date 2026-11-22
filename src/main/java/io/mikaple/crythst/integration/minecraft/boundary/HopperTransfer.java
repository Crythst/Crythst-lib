package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * A single vanilla-parity item move between two containers.
 *
 * <p>Vanilla hopper logic lives in private static methods of
 * {@link HopperBlockEntity}. The public {@code addItem} overload is the only
 * stable entry point, so the bridge recreates the small extraction loop around
 * it. Moving one item per operation and restoring the original stack on a
 * failed insert is what makes hopper cooldowns and item counts behave exactly
 * like vanilla.</p>
 *
 * <p>Both containers are live container objects, which is what preserves item
 * components and container NBT across the boundary: no stack is re-created from
 * a block-state snapshot.</p>
 */
public final class HopperTransfer {
    private HopperTransfer() {
    }

    /** Slots a hopper may extract from a source above it. */
    public static int[] extractSlots(Container container) {
        if (container instanceof WorldlyContainer worldly) {
            return worldly.getSlotsForFace(Direction.DOWN);
        }
        final int size = container.getContainerSize();
        final int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    /**
     * Pulls at most one item from {@code source} into a hopper below/at
     * {@code target}. Mirrors {@code HopperBlockEntity#suckInItems}.
     *
     * @return whether one item moved
     */
    public static boolean pull(Container source, Container target) {
        if (source == null || target == null || source == target || source.isEmpty()) {
            return false;
        }
        for (int slot : extractSlots(source)) {
            final ItemStack stack = source.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!target.canTakeItem(source, slot, stack)) {
                continue;
            }
            if (source instanceof WorldlyContainer worldly
                    && !worldly.canTakeItemThroughFace(slot, stack, Direction.DOWN)) {
                continue;
            }
            final ItemStack original = stack.copy();
            final ItemStack removed = source.removeItem(slot, 1);
            if (removed.isEmpty()) {
                continue;
            }
            final ItemStack remainder = HopperBlockEntity.addItem(
                    source, target, removed, Direction.DOWN);
            if (remainder.isEmpty()) {
                source.setChanged();
                target.setChanged();
                return true;
            }
            // The destination rejected the item. Vanilla restores the original
            // stack so a full/blocked slot never loses items. Restore a copy:
            // removeItem may have mutated the live stack in the source slot.
            source.setItem(slot, original);
        }
        return false;
    }

    /**
     * Pushes at most one item from a hopper into {@code target}. Mirrors
     * {@code HopperBlockEntity#ejectItems}.
     *
     * @param targetFace the face of the target that receives the item; vanilla
     *                   passes {@code facing.getOpposite()}
     * @return whether one item moved
     */
    public static boolean push(Container source, Container target, Direction targetFace) {
        if (source == null || target == null || source == target || source.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            final ItemStack stack = source.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final ItemStack original = stack.copy();
            final ItemStack removed = source.removeItem(slot, 1);
            if (removed.isEmpty()) {
                continue;
            }
            final ItemStack remainder = HopperBlockEntity.addItem(
                    source, target, removed, targetFace == null ? Direction.DOWN : targetFace);
            if (remainder.isEmpty()) {
                source.setChanged();
                target.setChanged();
                return true;
            }
            source.setItem(slot, original);
        }
        return false;
    }
}
