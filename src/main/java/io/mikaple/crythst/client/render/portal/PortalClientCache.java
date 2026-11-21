package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalSnapshot;

import java.util.List;

/**
 * Thread-safe hand-off from the client-tick snapshot updater to the render
 * thread.
 *
 * <p>The list is immutable and swapped atomically. The renderer never talks to
 * {@code PortalManager} or to the integrated server.</p>
 */
public final class PortalClientCache {
    private static volatile List<PortalSnapshot> snapshots = List.of();

    private PortalClientCache() {
    }

    public static List<PortalSnapshot> get() {
        return snapshots;
    }

    public static void set(List<PortalSnapshot> newSnapshots) {
        snapshots = newSnapshots == null ? List.of() : List.copyOf(newSnapshots);
    }

    public static void clear() {
        snapshots = List.of();
    }
}
