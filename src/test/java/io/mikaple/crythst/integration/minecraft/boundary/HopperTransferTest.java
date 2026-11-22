package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopperTransferTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A dedicated server binds item components from the data pack. Unit
        // tests only need the stack-size component; without it ItemStack
        // construction fails closed with "Components not bound yet".
        bind(Items.STONE);
        bind(Items.IRON_INGOT);
        bind(Items.GOLD_INGOT);
        bind(Items.DIRT);
    }

    private static void bind(Item item) {
        item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64)
                .build());
    }

    @Test
    void pullMovesExactlyOneItemAndPreservesTheRest() {
        final SimpleContainer source = new SimpleContainer(1);
        final SimpleContainer target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(Items.STONE, 64));

        assertTrue(HopperTransfer.pull(source, target));
        assertEquals(63, source.getItem(0).getCount());
        assertEquals(Items.STONE, target.getItem(0).getItem());
        assertEquals(1, target.getItem(0).getCount());

        assertTrue(HopperTransfer.pull(source, target));
        assertEquals(62, source.getItem(0).getCount());
        assertEquals(2, target.getItem(0).getCount());
    }

    @Test
    void pushMergesIntoAnExistingStack() {
        final SimpleContainer source = new SimpleContainer(2);
        final SimpleContainer target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 10));

        assertTrue(HopperTransfer.push(source, target, Direction.DOWN));
        assertEquals(2, source.getItem(0).getCount());
        assertEquals(11, target.getItem(0).getCount());
    }

    @Test
    void fullDestinationLosesNoItems() {
        final SimpleContainer source = new SimpleContainer(1);
        final SimpleContainer target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
        target.setItem(0, new ItemStack(Items.GOLD_INGOT, 64));

        assertFalse(HopperTransfer.push(source, target, Direction.DOWN));
        assertEquals(5, source.getItem(0).getCount());
        assertEquals(64, target.getItem(0).getCount());
    }

    @Test
    void incompatibleItemDoesNotMove() {
        final SimpleContainer source = new SimpleContainer(1);
        final SimpleContainer target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(Items.STONE, 1));
        target.setItem(0, new ItemStack(Items.DIRT, 64));

        assertFalse(HopperTransfer.push(source, target, Direction.DOWN));
        assertEquals(1, source.getItem(0).getCount());
        assertEquals(64, target.getItem(0).getCount());
    }
}
