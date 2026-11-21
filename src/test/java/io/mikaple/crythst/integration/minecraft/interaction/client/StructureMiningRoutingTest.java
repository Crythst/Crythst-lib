package io.mikaple.crythst.integration.minecraft.interaction.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureMiningRoutingTest {
    @Test
    void structureWinsOverWaterForShovelClicks() {
        assertEquals(StructureMiningController.AttackRoute.MINE,
                StructureMiningController.route(true, true));
        assertEquals(StructureMiningController.AttackRoute.MINE,
                StructureMiningController.route(true, false));
    }

    @Test
    void waterOnlyAppliesWithoutAStructureTarget() {
        assertEquals(StructureMiningController.AttackRoute.SHOVEL_WATER,
                StructureMiningController.route(false, true));
    }

    @Test
    void noTargetLeavesVanillaAlone() {
        assertEquals(StructureMiningController.AttackRoute.PASS,
                StructureMiningController.route(false, false));
    }
}
