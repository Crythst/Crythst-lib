package io.mikaple.crythst.integration.minecraft.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureFeedbackTest {
    @Test
    void breakCountScalesWithBlockScale() {
        assertEquals(12, StructureFeedback.breakParticleCount(1.0));
        assertTrue(StructureFeedback.breakParticleCount(2.0)
                > StructureFeedback.breakParticleCount(1.0));
        assertTrue(StructureFeedback.breakParticleCount(0.5)
                < StructureFeedback.breakParticleCount(1.0));
    }

    @Test
    void placeCountIsSmallerThanBreakCount() {
        for (double scale = 0.5; scale <= 3.0; scale += 0.5) {
            assertTrue(StructureFeedback.placeParticleCount(scale)
                    < StructureFeedback.breakParticleCount(scale));
        }
    }

    @Test
    void spreadScalesAndClamps() {
        assertEquals(0.25, StructureFeedback.particleSpread(1.0), 1.0e-9);
        assertTrue(StructureFeedback.particleSpread(2.0)
                > StructureFeedback.particleSpread(1.0));
        assertEquals(StructureFeedback.particleSpread(3.0),
                StructureFeedback.particleSpread(100.0), 1.0e-9);
        assertEquals(StructureFeedback.particleSpread(0.5),
                StructureFeedback.particleSpread(0.1), 1.0e-9);
    }

    @Test
    void invalidScaleFallsBackToNormalVoxel() {
        assertEquals(StructureFeedback.breakParticleCount(1.0),
                StructureFeedback.breakParticleCount(Double.NaN));
        assertEquals(StructureFeedback.particleSpread(1.0),
                StructureFeedback.particleSpread(-4.0), 1.0e-9);
    }
}
