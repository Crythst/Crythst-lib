package io.mikaple.crythst.integration.minecraft.interaction.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningProgressTest {
    private static final Object TARGET_A = new Object();
    private static final Object TARGET_B = new Object();
    private static final Object TOOL = new Object();

    @Test
    void survivalOnlyBreaksAtTheThreshold() {
        final MiningProgress progress = new MiningProgress();

        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_A, TOOL, 0.4F, false));
        assertEquals(0.4F, progress.progress(), 1.0e-6F);
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_A, TOOL, 0.4F, false));
        assertEquals(0.8F, progress.progress(), 1.0e-6F);

        assertEquals(MiningProgress.Step.BREAK, progress.tick(TARGET_A, TOOL, 0.4F, false));
        assertEquals(0.0F, progress.progress(), 1.0e-6F);
        assertTrue(progress.isCompleted());
        assertFalse(progress.isMining());
    }

    @Test
    void aCompletedTargetDoesNotEmitAgainUntilItChanges() {
        final MiningProgress progress = new MiningProgress();

        assertEquals(MiningProgress.Step.BREAK, progress.tick(TARGET_A, TOOL, 1.0F, false));
        // The server may not have removed the voxel for one or two ticks. The
        // same stale target must not produce repeated MINE packets.
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_A, TOOL, 1.0F, false));
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_A, TOOL, 1.0F, false));

        // Moving to another voxel starts a fresh session and can break again.
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_B, TOOL, 0.3F, false));
        assertEquals(MiningProgress.Step.BREAK, progress.tick(TARGET_B, TOOL, 0.8F, false));
    }

    @Test
    void creativeBreaksImmediatelyAndOnlyOncePerTarget() {
        final MiningProgress progress = new MiningProgress();

        assertEquals(MiningProgress.Step.BREAK, progress.tick(TARGET_A, TOOL, 0.0F, true));
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_A, TOOL, 0.0F, true));
        assertEquals(MiningProgress.Step.BREAK, progress.tick(TARGET_B, TOOL, 0.0F, true));
    }

    @Test
    void changingTargetOrToolResetsAccumulatedProgress() {
        final MiningProgress progress = new MiningProgress();
        progress.tick(TARGET_A, TOOL, 0.8F, false);

        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_B, TOOL, 0.4F, false));
        assertEquals(0.4F, progress.progress(), 1.0e-6F);

        final Object otherTool = new Object();
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_B, otherTool, 0.4F, false));
        assertEquals(0.4F, progress.progress(), 1.0e-6F);

        // Switching back to A starts over; it does not reuse the old 0.8.
        assertEquals(MiningProgress.Step.NONE, progress.tick(TARGET_A, TOOL, 0.5F, false));
        assertEquals(0.5F, progress.progress(), 1.0e-6F);
    }

    @Test
    void losingTheTargetResetsEverything() {
        final MiningProgress progress = new MiningProgress();
        progress.tick(TARGET_A, TOOL, 0.7F, false);
        assertTrue(progress.isMining());

        assertEquals(MiningProgress.Step.NONE, progress.tick(null, TOOL, 0.5F, false));
        assertFalse(progress.hasTarget());
        assertFalse(progress.isMining());
        assertFalse(progress.isCompleted());
        assertEquals(0.0F, progress.progress(), 1.0e-6F);
        assertEquals(-1, progress.stage());
    }

    @Test
    void resetClearsAStartedSession() {
        final MiningProgress progress = new MiningProgress();
        progress.tick(TARGET_A, TOOL, 0.6F, false);
        progress.reset();
        assertFalse(progress.hasTarget());
        assertEquals(0.0F, progress.progress(), 1.0e-6F);
    }
}
