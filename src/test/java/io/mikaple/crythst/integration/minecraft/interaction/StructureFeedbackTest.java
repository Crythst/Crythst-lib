package io.mikaple.crythst.integration.minecraft.interaction;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void placementHasNoParticleBurst() {
        // Placement only emits the place sound. The former place-particle
        // count API must stay gone so a future edit cannot silently bring the
        // puff back without updating this contract.
        assertFalse(Arrays.stream(StructureFeedback.class.getMethods())
                        .anyMatch(method -> method.getName().equals("placeParticleCount")),
                "placement must not expose or emit a particle burst");
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
    void breakDoesNotExposeTheRoundedVanillaLevelEvent() {
        // Break feedback now uses exact pose-derived positions. Keeping the
        // 2001 event constant would invite a duplicate, BlockPos-rounded burst.
        assertFalse(Arrays.stream(StructureFeedback.class.getFields())
                        .anyMatch(field -> field.getName().equals("BREAK_LEVEL_EVENT")),
                "break feedback must not advertise the BlockPos level-event path");
    }

    @Test
    void invalidScaleFallsBackToNormalVoxel() {
        assertEquals(StructureFeedback.breakParticleCount(1.0),
                StructureFeedback.breakParticleCount(Double.NaN));
        assertEquals(StructureFeedback.particleSpread(1.0),
                StructureFeedback.particleSpread(-4.0), 1.0e-9);
    }
}
