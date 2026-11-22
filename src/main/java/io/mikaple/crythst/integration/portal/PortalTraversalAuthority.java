package io.mikaple.crythst.integration.portal;

import org.joml.Vector3dc;

/**
 * Pure server-authority policy for a client traversal notice.
 *
 * <p>The server's own swept segment wins. If the server has not yet received
 * the movement that produced the crossing (network latency), the client
 * prediction is accepted only when its intersection point is physically close
 * to the authoritative body centre.</p>
 */
public final class PortalTraversalAuthority {
    private PortalTraversalAuthority() {
    }

    public static boolean accepts(boolean serverCrossingInside,
                                  boolean clientCrossingInside,
                                  Vector3dc clientCrossingPoint,
                                  Vector3dc serverBodyCentre,
                                  double maxClientDistance) {
        if (serverCrossingInside) {
            return true;
        }
        return clientCrossingInside
                && PortalTeleportMath.isWithin(
                        clientCrossingPoint, serverBodyCentre, maxClientDistance);
    }
}
