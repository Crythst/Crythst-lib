package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.integration.portal.PortalAnchor;
import io.mikaple.crythst.integration.portal.PortalPlane;
import io.mikaple.crythst.integration.portal.PortalShape;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.portal.PortalSyncPayload;

import java.util.ArrayList;
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

    /**
     * Snapshots with carrier-anchored planes resolved against the interpolated
     * body pose at {@code nowNanos}.
     *
     * <p>This is the accessor renderers and crossing prediction must use. A
     * portal attached to a physics structure has no fixed world plane; the
     * server only ships the anchor, and the plane has to be re-sampled every
     * frame from {@link ClientStructureCache} or the portal would visibly lag
     * behind its carrier. When nothing is anchored the cached list is returned
     * untouched.</p>
     */
    public static List<PortalSnapshot> effective(long nowNanos) {
        final List<PortalSnapshot> current = snapshots;
        boolean anyMoving = false;
        for (PortalSnapshot snapshot : current) {
            if (snapshot.moving()) {
                anyMoving = true;
                break;
            }
        }
        if (!anyMoving) {
            return current;
        }
        final List<PortalSnapshot> resolved = new ArrayList<>(current.size());
        for (PortalSnapshot snapshot : current) {
            resolved.add(resolve(snapshot, nowNanos));
        }
        return List.copyOf(resolved);
    }

    private static PortalSnapshot resolve(PortalSnapshot snapshot, long nowNanos) {
        if (!snapshot.moving()) {
            return snapshot;
        }
        return snapshot.withPlanes(
                resolvePlane(snapshot.anchor(), snapshot.plane(), nowNanos),
                resolveShape(snapshot.anchor(), snapshot.shape(), nowNanos),
                resolvePlane(snapshot.destinationAnchor(), snapshot.destinationPlane(), nowNanos),
                resolveShape(snapshot.destinationAnchor(), snapshot.destinationShape(), nowNanos));
    }

    /**
     * The carrier's interpolated pose, or {@code null} when this client does not
     * have the carrier streamed yet. {@code interpolatedPose} answers with
     * {@link Transform3d#IDENTITY} for unknown bodies, which would park the
     * portal at the world origin, so the presence check matters.
     */
    private static Transform3d carrierPose(PortalAnchor anchor, long nowNanos) {
        if (anchor == null || ClientStructureCache.get(anchor.carrierBodyId()) == null) {
            return null;
        }
        return ClientStructureCache.interpolatedPose(anchor.carrierBodyId(), nowNanos);
    }

    private static PortalPlane resolvePlane(PortalAnchor anchor, PortalPlane fallback,
                                            long nowNanos) {
        final Transform3d pose = carrierPose(anchor, nowNanos);
        return pose == null ? fallback : anchor.worldPlane(pose);
    }

    private static PortalShape resolveShape(PortalAnchor anchor, PortalShape fallback,
                                            long nowNanos) {
        if (fallback == null) {
            return null;
        }
        final Transform3d pose = carrierPose(anchor, nowNanos);
        return pose == null ? fallback : fallback.withPlane(anchor.worldPlane(pose));
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
