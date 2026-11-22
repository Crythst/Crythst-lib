package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FluidBoundaryPolicyTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @Test
    void structureFluidSpillsIntoAir() {
        assertEquals(FluidBoundaryPolicy.Action.FLUID_TO_HOST, decide(WATER, AIR, false));
    }

    @Test
    void hostFluidMirrorsIntoAnAirStructureCell() {
        assertEquals(FluidBoundaryPolicy.Action.FLUID_TO_STRUCTURE,
                decide(AIR, WATER, false));
    }

    @Test
    void solidBoundariesNeverExchangeFluid() {
        assertEquals(FluidBoundaryPolicy.Action.NONE, decide(STONE, WATER, false));
        assertEquals(FluidBoundaryPolicy.Action.NONE, decide(WATER, STONE, false));
    }

    @Test
    void bridgeOwnedHostFluidIsRemovedWhenTheStructureStopsSupplyingIt() {
        assertEquals(FluidBoundaryPolicy.Action.REMOVE_HOST_FLUID,
                decide(AIR, WATER, true));
        assertEquals(FluidBoundaryPolicy.Action.NONE,
                decide(WATER, WATER, true),
                "while the structure still has fluid the mirror stays");
    }

    @Test
    void existingFluidNeverDuplicates() {
        assertEquals(FluidBoundaryPolicy.Action.NONE, decide(WATER, WATER, false));
        assertEquals(FluidBoundaryPolicy.Action.NONE, decide(AIR, AIR, false));
    }

    private static FluidBoundaryPolicy.Action decide(BlockState inside, BlockState host,
                                                     boolean bridgeOwned) {
        return FluidBoundaryPolicy.decide(inside, host,
                inside.getFluidState(), host.getFluidState(), bridgeOwned);
    }
}
