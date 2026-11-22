package io.mikaple.crythst.client.render.portal;

/**
 * Immutable snapshot of the portal renderer's per-frame cost.
 *
 * <p>Published by {@link PortalRenderManager} and read by the
 * {@code crythst:portal_stats} F3 entry. The two timings measure work on the
 * render thread, not GPU time: {@code extractMillis} covers the destination
 * extraction that builds the mask and chunk-section list before the frame, and
 * {@code passMillis} covers recording the portal framegraph pass.</p>
 *
 * @param portals        root portals rendered this frame
 * @param maxLayer       deepest recursion layer rendered (0 when idle)
 * @param sections       chunk sections extracted for all layers
 * @param extractMillis  destination-extraction duration in milliseconds
 * @param passMillis     portal-pass recording duration in milliseconds
 * @param sampledAtNanos {@link System#nanoTime()} when this snapshot was taken
 */
public record PortalRenderStats(int portals, int maxLayer, int sections,
                                double extractMillis, double passMillis,
                                long sampledAtNanos) {
    /** Idle snapshot: nothing rendered, no timings. */
    public static final PortalRenderStats EMPTY = new PortalRenderStats(0, 0, 0, 0.0, 0.0, 0L);

    public PortalRenderStats {
        portals = Math.max(0, portals);
        maxLayer = Math.max(0, maxLayer);
        sections = Math.max(0, sections);
        extractMillis = finiteOrZero(extractMillis);
        passMillis = finiteOrZero(passMillis);
        sampledAtNanos = Math.max(0L, sampledAtNanos);
    }

    /** Whether no portal was rendered this frame. */
    public boolean idle() {
        return portals == 0;
    }

    /** Returns a copy with the portal-pass recording time replaced. */
    public PortalRenderStats withPassMillis(double millis, long nowNanos) {
        return new PortalRenderStats(portals, maxLayer, sections, extractMillis, millis, nowNanos);
    }

    /** Age of this snapshot in milliseconds relative to {@code nowNanos}. */
    public double ageMillis(long nowNanos) {
        if (sampledAtNanos <= 0L || nowNanos <= 0L) {
            return 0.0;
        }
        return Math.max(0L, nowNanos - sampledAtNanos) / 1.0e6;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
