package io.mikaple.crythst.client.render.portal;

import java.util.List;
import java.util.Locale;

/**
 * Text lines for the {@code crythst:portal_stats} F3 entry.
 *
 * <p>Lives in this package because {@link PortalCaptureSettings} is
 * package-private; the debug entry only renders the returned strings.</p>
 */
public final class PortalDebugText {
    private PortalDebugText() {
    }

    /** Current per-frame portal renderer cost and the active tuning knobs. */
    public static List<String> lines() {
        final PortalRenderStats stats = PortalRenderManager.stats();
        final long now = System.nanoTime();
        return List.of(
                String.format(Locale.ROOT,
                        "Crythst portals: %d rendered, max layer %d, %d sections",
                        stats.portals(), stats.maxLayer(), stats.sections()),
                String.format(Locale.ROOT,
                        "  extract %.2f ms, pass record %.2f ms, sampled %.0f ms ago",
                        stats.extractMillis(), stats.passMillis(), stats.ageMillis(now)),
                String.format(Locale.ROOT,
                        "  recursion cap %d, per-round %d, targets %d, scale %.2f",
                        PortalCaptureSettings.MAX_RECURSION,
                        PortalCaptureSettings.MAX_PORTALS_PER_ROUND,
                        PortalCaptureSettings.MAX_ACTIVE_TARGETS,
                        PortalCaptureSettings.SCALE));
    }
}
