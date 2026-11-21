package io.mikaple.crythst.integration.portal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Immutable cross-thread view of a {@link Portal}.
 *
 * <p>The snapshot also carries the destination plane and dimension when the
 * portal is linked. That lets the client renderer build the view transform
 * without ever touching the server-side {@link PortalManager}.</p>
 */
public record PortalSnapshot(UUID id, String name, ResourceKey<Level> dimension,
                             PortalPlane plane, UUID linkedPortalId,
                             PortalPlane destinationPlane,
                             ResourceKey<Level> destinationDimension) {
    public boolean linked() {
        return linkedPortalId != null && destinationPlane != null;
    }

    public boolean viewableIn(ResourceKey<Level> currentDimension) {
        return linked() && currentDimension.equals(destinationDimension);
    }

    public PortalSnapshot withLinkedPortalId(UUID linked) {
        return new PortalSnapshot(id, name, dimension, plane, linked,
                destinationPlane, destinationDimension);
    }

    public static PortalSnapshot of(Portal portal, Portal destination) {
        return new PortalSnapshot(
                portal.id(),
                portal.name(),
                portal.dimension(),
                portal.plane(),
                portal.linkedPortalId(),
                destination == null ? null : destination.plane(),
                destination == null ? null : destination.dimension());
    }
}
