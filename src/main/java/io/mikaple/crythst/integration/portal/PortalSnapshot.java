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
 *
 * <p>{@code anchor} and {@code destinationAnchor} are the moving-carrier
 * bindings. They travel with the snapshot so the client can re-sample the plane
 * from {@code ClientStructureCache} every frame instead of waiting for the
 * server to republish a plane 20 times per second. A snapshot with anchors set
 * must be resolved through {@link #withPlanes} before its plane is used for
 * rendering or crossing tests.</p>
 */
public record PortalSnapshot(UUID id, String name, ResourceKey<Level> dimension,
                             PortalPlane plane, PortalShape shape, UUID linkedPortalId,
                             PortalPlane destinationPlane,
                             PortalShape destinationShape,
                             ResourceKey<Level> destinationDimension,
                             PortalAnchor anchor,
                             PortalAnchor destinationAnchor) {
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

    /** True when either side of this portal rides on a moving carrier. */
    public boolean moving() {
        return anchor != null || destinationAnchor != null;
    }

    public PortalSnapshot withLinkedPortalId(UUID linked) {
        return new PortalSnapshot(id, name, dimension, plane, shape, linked,
                destinationPlane, destinationShape, destinationDimension, anchor, destinationAnchor);
    }

    /**
     * Returns a copy with resolved world planes.
     *
     * <p>Used by the client every frame after sampling the carrier poses, and by
     * the server when it publishes snapshots. Pass {@code null} destination
     * values as-is when the portal is unlinked.</p>
     */
    public PortalSnapshot withPlanes(PortalPlane entryPlane, PortalShape entryShape,
                                     PortalPlane exitPlane, PortalShape exitShape) {
        return new PortalSnapshot(id, name, dimension,
                entryPlane == null ? plane : entryPlane,
                entryShape == null ? shape : entryShape,
                linkedPortalId,
                exitPlane == null ? destinationPlane : exitPlane,
                exitShape == null ? destinationShape : exitShape,
                destinationDimension, anchor, destinationAnchor);
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
                destination == null ? null : destination.dimension(),
                portal.anchor(),
                destination == null ? null : destination.anchor());
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
                destinationDimension, null, null);
    }
}
