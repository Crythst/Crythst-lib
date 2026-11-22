package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.network.portal.PortalSyncPayload;

import java.util.List;

/**
 * Thread-safe hand-off from the client tick/network receiver to the render
 * thread.
 *
 * <p>The list is immutable and swapped atomically. The renderer never talks to
 * {@code PortalManager} or to the integrated server. In multiplayer the list is
 * filled by {@code PortalSyncClient}; in singleplayer the same S2C packet is
 * still used, with a local-server fallback while the first packet is in flight.</p>
 */
public final class PortalClientCache {
    private static volatile List<PortalSnapshot> snapshots = List.of();
    private static volatile boolean networkActive;
    private static volatile long revision = -1L;

    private PortalClientCache() {
    }

    public static List<PortalSnapshot> get() {
        return snapshots;
    }

    public static void set(List<PortalSnapshot> newSnapshots) {
        snapshots = newSnapshots == null ? List.of() : List.copyOf(newSnapshots);
    }

    /** Applies one authoritative S2C snapshot on the client thread. */
    public static void setFromNetwork(PortalSyncPayload payload) {
        if (payload == null) {
            return;
        }
        snapshots = List.copyOf(payload.portals());
        revision = payload.revision();
        networkActive = true;
    }

    /** True once at least one server snapshot has been received this session. */
    public static boolean networkActive() {
        return networkActive;
    }

    public static long revision() {
        return revision;
    }

    /** Called on disconnect so the next server starts from a clean slate. */
    public static void clearNetworkActive() {
        networkActive = false;
        revision = -1L;
    }

    public static void clear() {
        snapshots = List.of();
    }
}
