package io.mikaple.crythst.integration.minecraft.operation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidMiningSessionTest {
    private static final VoidMiningSession.Target TARGET_A =
            new VoidMiningSession.Target(1L, 2, 3, 4);
    private static final VoidMiningSession.Target TARGET_B =
            new VoidMiningSession.Target(1L, 2, 3, 5);
    private static final Object TOOL = new Object();

    @Test
    void creativeCompletesOnStartAndOnlyOnce() {
        final VoidMiningSession session = new VoidMiningSession();
        assertEquals(VoidMiningSession.Step.BREAK,
                session.start(10L, TARGET_A, TOOL, true));
        assertTrue(session.completed());
        assertEquals(1.0F, session.progress(), 1.0e-6F);

        // A repeated START/heartbeat/tick for the completed target must not
        // request a second break.
        assertEquals(VoidMiningSession.Step.NONE,
                session.start(10L, TARGET_A, TOOL, true));
        assertEquals(VoidMiningSession.Step.NONE,
                session.heartbeat(11L, TARGET_A, TOOL));
        assertEquals(VoidMiningSession.Step.NONE, session.tick(11L, 1.0F));
    }

    @Test
    void survivalLongHoldAccumulatesUntilCompletion() {
        final VoidMiningSession session = new VoidMiningSession();
        assertEquals(VoidMiningSession.Step.STARTED,
                session.start(100L, TARGET_A, TOOL, false));

        // Ten held ticks at 0.1 progress: only the last one completes.
        for (int i = 1; i <= 9; i++) {
            final long now = 100L + i;
            assertEquals(VoidMiningSession.Step.NONE,
                    session.heartbeat(now, TARGET_A, TOOL));
            assertEquals(VoidMiningSession.Step.PROGRESSING,
                    session.tick(now, 0.1F));
            assertTrue(session.active());
        }
        assertEquals(VoidMiningSession.Step.NONE,
                session.heartbeat(110L, TARGET_A, TOOL));
        assertEquals(VoidMiningSession.Step.BREAK, session.tick(110L, 0.1F));
        assertTrue(session.completed());
        assertEquals(1.0F, session.progress(), 1.0e-6F);

        // The same tick is never advanced twice, and a completed session never
        // emits another break.
        assertEquals(VoidMiningSession.Step.NONE, session.tick(110L, 0.1F));
        assertEquals(VoidMiningSession.Step.NONE, session.tick(111L, 0.1F));
    }

    @Test
    void stopAbortsBeforeCompletion() {
        final VoidMiningSession session = new VoidMiningSession();
        session.start(0L, TARGET_A, TOOL, false);
        assertEquals(VoidMiningSession.Step.PROGRESSING, session.tick(1L, 0.4F));

        assertEquals(VoidMiningSession.Step.ABORTED, session.stop());
        assertFalse(session.active());
        assertEquals(VoidMiningSession.Step.NONE, session.tick(2L, 1.0F));
        assertFalse(session.completed());
    }

    @Test
    void missingHeartbeatExpiresTheSession() {
        final VoidMiningSession session = new VoidMiningSession();
        session.start(0L, TARGET_A, TOOL, false);

        final int timeout = VoidMiningSession.HEARTBEAT_TIMEOUT_TICKS;
        assertEquals(VoidMiningSession.Step.PROGRESSING, session.tick(1L, 0.1F));
        assertEquals(VoidMiningSession.Step.EXPIRED,
                session.tick(1L + timeout + 1L, 0.1F));
        assertFalse(session.active());
    }

    @Test
    void changingTargetOrToolRestartsAtZero() {
        final VoidMiningSession session = new VoidMiningSession();
        session.start(0L, TARGET_A, TOOL, false);
        session.tick(1L, 0.8F);
        assertEquals(0.8F, session.progress(), 1.0e-6F);

        // A different target starts a fresh session.
        assertEquals(VoidMiningSession.Step.STARTED,
                session.start(2L, TARGET_B, TOOL, false));
        assertEquals(0.0F, session.progress(), 1.0e-6F);

        session.tick(3L, 0.5F);
        final Object otherTool = new Object();
        assertEquals(VoidMiningSession.Step.STARTED,
                session.start(4L, TARGET_B, otherTool, false));
        assertEquals(0.0F, session.progress(), 1.0e-6F);
    }

    @Test
    void duplicateStartForSameTargetKeepsProgress() {
        final VoidMiningSession session = new VoidMiningSession();
        session.start(0L, TARGET_A, TOOL, false);
        session.tick(1L, 0.4F);

        assertEquals(VoidMiningSession.Step.NONE,
                session.start(1L, TARGET_A, TOOL, false));
        assertEquals(0.4F, session.progress(), 1.0e-6F);
        // Duplicate START still counts as a heartbeat.
        assertEquals(VoidMiningSession.Step.NONE, session.checkHeartbeat(2L));
    }

    @Test
    void heartbeatMismatchAborts() {
        final VoidMiningSession session = new VoidMiningSession();
        session.start(0L, TARGET_A, TOOL, false);
        assertEquals(VoidMiningSession.Step.ABORTED,
                session.heartbeat(1L, TARGET_B, TOOL));
        assertFalse(session.active());
    }
}
