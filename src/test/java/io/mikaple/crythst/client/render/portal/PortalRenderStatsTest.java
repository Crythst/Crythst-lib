package io.mikaple.crythst.client.render.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clamping and bookkeeping tests for the portal render statistics shown by the
 * {@code crythst:portal_stats} F3 entry.
 */
class PortalRenderStatsTest {
    private static final double EPS = 1.0e-9;

    @Test
    void emptyIsIdle() {
        assertTrue(PortalRenderStats.EMPTY.idle());
        assertEquals(0, PortalRenderStats.EMPTY.portals());
        assertEquals(0.0, PortalRenderStats.EMPTY.extractMillis(), EPS);
    }

    @Test
    void negativeAndNonFiniteValuesAreDropped() {
        final PortalRenderStats stats = new PortalRenderStats(-3, -1, -8,
                Double.NaN, Double.NEGATIVE_INFINITY, -5L);
        assertEquals(0, stats.portals());
        assertEquals(0, stats.maxLayer());
        assertEquals(0, stats.sections());
        assertEquals(0.0, stats.extractMillis(), EPS);
        assertEquals(0.0, stats.passMillis(), EPS);
        assertEquals(0L, stats.sampledAtNanos());
        assertTrue(stats.idle());
    }

    @Test
    void passTimingKeepsFrameCounters() {
        final PortalRenderStats extract = new PortalRenderStats(2, 2, 41, 1.5, 0.0, 1_000L);
        final PortalRenderStats withPass = extract.withPassMillis(0.25, 2_000L);
        assertEquals(2, withPass.portals());
        assertEquals(2, withPass.maxLayer());
        assertEquals(41, withPass.sections());
        assertEquals(1.5, withPass.extractMillis(), EPS);
        assertEquals(0.25, withPass.passMillis(), EPS);
        assertEquals(2_000L, withPass.sampledAtNanos());
        assertFalse(withPass.idle());
    }

    @Test
    void ageIsMeasuredFromTheSampleTime() {
        final PortalRenderStats stats = new PortalRenderStats(1, 1, 4, 0.1, 0.1, 1_000_000L);
        assertEquals(5.0, stats.ageMillis(6_000_000L), EPS);
        assertEquals(0.0, stats.ageMillis(500_000L), EPS, "a future sample must not go negative");
        assertEquals(0.0, PortalRenderStats.EMPTY.ageMillis(9_999_999L), EPS);
    }
}
