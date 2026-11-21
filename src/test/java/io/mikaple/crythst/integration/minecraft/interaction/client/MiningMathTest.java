package io.mikaple.crythst.integration.minecraft.interaction.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningMathTest {
    @Test
    void stoneWithCorrectToolUsesVanillaSpeedDivisor() {
        // Diamond pickaxe speed 8 on stone hardness 1.5:
        // 8 / 1.5 / 30 = 0.1777... per tick (about 6 ticks).
        final float perTick = MiningMath.progressPerTick(1.5F, 8.0F, true);
        assertEquals(8.0F / 1.5F / 30.0F, perTick, 1.0e-6F);
        assertEquals(6, (int) Math.ceil(1.0F / perTick));
    }

    @Test
    void wrongToolUsesTheHundredDivisor() {
        final float correct = MiningMath.progressPerTick(1.5F, 8.0F, true);
        final float wrong = MiningMath.progressPerTick(1.5F, 8.0F, false);
        assertEquals(correct * 30.0F / 100.0F, wrong, 1.0e-6F);
    }

    @Test
    void toolSpeedAndEffectsAreAlreadyInTheSuppliedDestroySpeed() {
        // Player#getDestroySpeed folds in the tool, Efficiency, Haste,
        // Mining Fatigue, the block-break-speed attribute, water and air.
        final float bareHand = MiningMath.progressPerTick(1.5F, 1.0F, false);
        final float haste = MiningMath.progressPerTick(1.5F, 2.2F, false);
        assertTrue(haste > bareHand);
        assertEquals(1.0F / 1.5F / 100.0F, bareHand, 1.0e-6F);
    }

    @Test
    void unbreakableAndInstantBlocksAreHandled() {
        assertEquals(0.0F, MiningMath.progressPerTick(-1.0F, 8.0F, true));
        assertEquals(Float.POSITIVE_INFINITY,
                MiningMath.progressPerTick(0.0F, 8.0F, true));
        assertEquals(0.0F, MiningMath.progressPerTick(0.0F, 0.0F, true));
    }

    @Test
    void advanceClampsAtOneAndIgnoresInvalidIncrements() {
        assertEquals(0.4F, MiningMath.advance(0.0F, 0.4F), 1.0e-6F);
        assertEquals(1.0F, MiningMath.advance(0.9F, 0.4F), 1.0e-6F);
        assertEquals(0.4F, MiningMath.advance(0.4F, -1.0F), 1.0e-6F);
        assertEquals(0.4F, MiningMath.advance(0.4F, 0.0F), 1.0e-6F);
        assertEquals(0.4F, MiningMath.advance(0.4F, Float.NaN), 1.0e-6F);
        assertEquals(1.0F, MiningMath.advance(0.4F, Float.POSITIVE_INFINITY), 1.0e-6F);
        assertTrue(MiningMath.isComplete(1.0F));
        assertFalse(MiningMath.isComplete(0.999F));
    }

    @Test
    void crackStagesMatchVanilla() {
        assertEquals(-1, MiningMath.stage(0.0F));
        assertEquals(-1, MiningMath.stage(-0.5F));
        assertEquals(0, MiningMath.stage(0.01F));
        assertEquals(4, MiningMath.stage(0.45F));
        assertEquals(9, MiningMath.stage(0.99F));
        assertEquals(9, MiningMath.stage(1.0F));
    }
}
