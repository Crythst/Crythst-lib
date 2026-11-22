package io.mikaple.crythst.client.render.portal;

/**
 * Adaptive capture cadence for the portal view textures.
 *
 * <p>The ideal is one destination render per main frame. That is the default:
 * the minimum interval is zero and a moved main camera or a changed portal
 * pose is captured immediately. If a capture costs more than the frame budget,
 * the interval backs off smoothly; when it is cheap again, the interval returns
 * to zero. A stationary camera still refreshes at a low rate so moving entities
 * and weather in the destination remain alive without paying for a full capture
 * every frame.</p>
 *
 * <p>Pure state machine; no clocks or render calls. The render manager passes
 * the monotonic timestamp in and decides how many targets to capture.</p>
 */
public final class PortalCaptureScheduler {
    private final long minIntervalNanos;
    private final long maxIntervalNanos;
    private final long idleRefreshNanos;

    private long intervalNanos;
    private long lastAttemptNanos;
    private double lastCostMs;

    public PortalCaptureScheduler(long minIntervalNanos, long maxIntervalNanos,
                                  long idleRefreshNanos) {
        if (minIntervalNanos < 0L || maxIntervalNanos < minIntervalNanos
                || idleRefreshNanos < 0L) {
            throw new IllegalArgumentException("Invalid capture interval configuration");
        }
        this.minIntervalNanos = minIntervalNanos;
        this.maxIntervalNanos = maxIntervalNanos;
        this.idleRefreshNanos = idleRefreshNanos;
        this.intervalNanos = minIntervalNanos;
    }

    /**
     * Whether a capture round may start now.
     *
     * @param now             monotonic time in nanoseconds
     * @param poseDirty       camera or portal pose changed since the last image
     * @param oldestImageAge  nanoseconds since the oldest visible target image
     */
    public boolean due(long now, boolean poseDirty, long oldestImageAge) {
        if (now < lastAttemptNanos) {
            // Clock reset (or a test passing a weird timestamp): allow a fresh
            // round rather than freezing forever.
            return true;
        }
        final boolean intervalElapsed = now - lastAttemptNanos >= intervalNanos;
        if (!intervalElapsed) {
            return false;
        }
        if (poseDirty) {
            return true;
        }
        return oldestImageAge >= idleRefreshNanos;
    }

    public void markAttempt(long now) {
        lastAttemptNanos = now;
    }

    /**
     * Feeds the measured cost (extract + render) of the last round back into
     * the interval. The thresholds are deliberately forgiving: a single
     * expensive frame should not drop the portal to 4 Hz.
     */
    public void recordCost(double totalMs) {
        if (!Double.isFinite(totalMs) || totalMs < 0.0) {
            return;
        }
        lastCostMs = totalMs;
        if (totalMs > 8.0) {
            intervalNanos = Math.min(maxIntervalNanos,
                    Math.max(minIntervalNanos, intervalNanos + 8_000_000L));
        } else if (totalMs > 5.0) {
            intervalNanos = Math.min(maxIntervalNanos,
                    Math.max(minIntervalNanos, intervalNanos + 3_000_000L));
        } else if (totalMs < 2.5) {
            intervalNanos = Math.max(minIntervalNanos, intervalNanos - 4_000_000L);
        } else if (totalMs < 4.0) {
            intervalNanos = Math.max(minIntervalNanos, intervalNanos - 1_000_000L);
        }
    }

    public long intervalNanos() {
        return intervalNanos;
    }

    public long idleRefreshNanos() {
        return idleRefreshNanos;
    }

    public double lastCostMs() {
        return lastCostMs;
    }
}
