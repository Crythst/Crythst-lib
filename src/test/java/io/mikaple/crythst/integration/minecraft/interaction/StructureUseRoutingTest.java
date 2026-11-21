package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure routing and failure tests for the new {@code USE} structure action.
 */
class StructureUseRoutingTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void handledItemsStillRouteToPlace() {
        assertEquals(StructureAction.PLACE, StructureItemUse.actionForUse(Items.STONE));
        assertEquals(StructureAction.PLACE, StructureItemUse.actionForUse(Items.COW_SPAWN_EGG));
        assertEquals(StructureAction.PLACE, StructureItemUse.actionForUse(Items.ITEM_FRAME));
    }

    @Test
    void ordinaryItemsRouteToUse() {
        assertEquals(StructureAction.USE, StructureItemUse.actionForUse(ItemStack.EMPTY));
        assertEquals(StructureAction.USE, StructureItemUse.actionForUse(Items.IRON_PICKAXE));
        assertEquals(StructureAction.USE, StructureItemUse.actionForUse(Items.WATER_BUCKET));
        assertEquals(StructureAction.USE, StructureItemUse.actionForUse(Items.BREAD));
    }

    @Test
    void realBlockInFrontWins() {
        final StructureBlockHitResult hit = hit(5.0);
        // The structure is 5 blocks away but a real block is only 4 away.
        assertFalse(StructureInteraction.isStructureInFront(hit, new Vec3(0.0, 0.0, 4.0),
                Vec3.ZERO));
        // A real block behind the structure does not occlude it.
        assertTrue(StructureInteraction.isStructureInFront(hit, new Vec3(0.0, 0.0, 6.0),
                Vec3.ZERO));
    }

    @Test
    void missingLiveBlockFailsWithoutConsumingTheClick() {
        // A null hit, and the null live-block result of an unloaded auxiliary
        // chunk, both take the same non-consuming failure path.
        final InteractionResult result = StructureInteraction.interactBlock(null, null);
        assertFalse(result.consumesAction());
    }

    private static StructureBlockHitResult hit(double distance) {
        return new StructureBlockHitResult(Vec3.ZERO, Direction.NORTH, BlockPos.ZERO,
                1L, Transform3d.identity(), 0, 0, 0, Direction.NORTH, 1.0,
                Blocks.STONE.defaultBlockState(), distance, null);
    }
}
