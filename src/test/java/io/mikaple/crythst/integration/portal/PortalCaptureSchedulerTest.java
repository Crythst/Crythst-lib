package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.client.render.portal.PortalCaptureScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCaptureSchedulerTest {
    private static final long MS = 1_000_000L;

    @Test
    void capturesImmediatelyWhenPoseChanges() {
        final PortalCaptureScheduler scheduler =
                new PortalCaptureScheduler(0L, 250L * MS, 100L * MS);
        assertTrue(scheduler.due(1_000L, true, 0L));
        scheduler.markAttempt(1_000L);
        assertTrue(scheduler.due(1_001L, true, 0L),
                "zero interval means the next frame may capture again");
    }

    @Test
    void reusesStaticImageUntilIdleRefresh() {
        final PortalCaptureScheduler scheduler =
                new PortalCaptureScheduler(0L, 250L * MS, 100L * MS);
        scheduler.markAttempt(1_000L);
        assertFalse(scheduler.due(50L * MS, false, 10L * MS));
        assertTrue(scheduler.due(101L * MS, false, 101L * MS));
    }

    @Test
    void backoffIsBoundedAndRecovers() {
        final PortalCaptureScheduler scheduler =
                new PortalCaptureScheduler(0L, 40L * MS, 100L * MS);
        scheduler.markAttempt(0L);
        scheduler.recordCost(20.0);
        assertTrue(scheduler.intervalNanos() > 0L);
        assertFalse(scheduler.due(1L * MS, true, 0L));

        for (int i = 0; i < 1000; i++) {
            scheduler.recordCost(20.0);
        }
        assertEquals(40L * MS, scheduler.intervalNanos());

        for (int i = 0; i < 1000; i++) {
            scheduler.recordCost(0.5);
        }
        assertEquals(0L, scheduler.intervalNanos());
        assertTrue(scheduler.due(41L * MS, true, 0L));
    }

    @Test
    void clockResetDoesNotFreezeCaptures() {
        final PortalCaptureScheduler scheduler =
                new PortalCaptureScheduler(10L * MS, 100L * MS, 100L * MS);
        scheduler.markAttempt(1_000L * MS);
        assertTrue(scheduler.due(1L, true, 0L));
    }

    @Test
    void invalidConfigurationIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PortalCaptureScheduler(10L, 5L, 0L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PortalCaptureScheduler(-1L, 5L, 0L));
    }
}
