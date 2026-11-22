package io.mikaple.crythst.integration.portal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Immutable cross-thread view of a {@link Portal}.
 *
 * <p>The snapshot also carries the destination plane, shape and dimension when
 * the portal is linked. That lets the client renderer build the view transform
 * without ever touching the server-side {@link PortalManager}.</p>
 */
public record PortalSnapshot(UUID id, String name, ResourceKey<Level> dimension,
                             PortalPlane plane, PortalShape shape, UUID linkedPortalId,
                             PortalPlane destinationPlane,
                             PortalShape destinationShape,
                             ResourceKey<Level> destinationDimension) {
    public boolean linked() {
        return linkedPortalId != null && destinationPlane != null;
    }

    /**
     * The destination world can be rendered only when it is the client's
     * currently loaded level. Cross-dimension traversal is supported by the
     * server, but this client has only one {@code ClientLevel} at a time.
     */
    public boolean viewableIn(ResourceKey<Level> currentDimension) {
        return linked() && currentDimension.equals(destinationDimension);
    }

    public PortalSnapshot withLinkedPortalId(UUID linked) {
        return new PortalSnapshot(id, name, dimension, plane, shape, linked,
                destinationPlane, destinationShape, destinationDimension);
    }

    public static PortalSnapshot of(Portal portal, Portal destination) {
        return new PortalSnapshot(
                portal.id(),
                portal.name(),
                portal.dimension(),
                portal.plane(),
                portal.shape(),
                portal.linkedPortalId(),
                destination == null ? null : destination.plane(),
                destination == null ? null : destination.shape(),
                destination == null ? null : destination.dimension());
    }

    /** Convenience constructor for tests/tools that only have a rectangle. */
    public static PortalSnapshot rectangular(UUID id, String name,
                                             ResourceKey<Level> dimension,
                                             PortalPlane plane, UUID linkedPortalId,
                                             PortalPlane destinationPlane,
                                             ResourceKey<Level> destinationDimension) {
        return new PortalSnapshot(id, name, dimension, plane,
                PortalShape.rectangle(plane), linkedPortalId,
                destinationPlane,
                destinationPlane == null ? null : PortalShape.rectangle(destinationPlane),
                destinationDimension);
    }
}
