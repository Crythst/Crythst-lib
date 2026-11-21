package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalSnapshot;

/**
 * Capture cadence and coarse cost feedback.
 *
 * <p>Starts at the configured base interval (100 ms by default) and backs off
 * toward 250 ms when an extract+render pair is expensive. The per-portal
 * freshness check handles the stationary case separately.</p>
 */
final class PortalCaptureTimings {
    private long intervalNanos = PortalCaptureSettings.BASE_INTERVAL_NANOS;
    private long lastAttemptNanos;
    private long lastLogNanos;

    long intervalNanos() {
        return intervalNanos;
    }

    boolean due(long now) {
        return now - lastAttemptNanos >= intervalNanos;
    }

    void markAttempt(long now) {
        lastAttemptNanos = now;
    }

    void record(PortalSnapshot portal, PortalViewTarget target, int sections,
                double extractMs, double renderMs, long now) {
        adapt(extractMs + renderMs);
        if (now - lastLogNanos < PortalCaptureSettings.TIMING_LOG_INTERVAL_NANOS) {
            return;
        }
        lastLogNanos = now;
        PortalRenderDiagnostics.timings(portal, target, sections, extractMs, renderMs,
                intervalNanos);
    }

    private void adapt(double totalMs) {
        if (totalMs > 12.0) {
            intervalNanos = Math.min(PortalCaptureSettings.MAX_INTERVAL_NANOS,
                    intervalNanos + 20_000_000L);
        } else if (totalMs < 6.0) {
            intervalNanos = Math.max(PortalCaptureSettings.BASE_INTERVAL_NANOS,
                    intervalNanos - 10_000_000L);
        }
    }
}
