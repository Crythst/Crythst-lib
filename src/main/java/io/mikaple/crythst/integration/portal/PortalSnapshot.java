package io.mikaple.crythst.integration.portal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Immutable cross-thread view of a {@link Portal}.
 *
 * <p>The server publishes snapshots; the client renderer only ever reads
 * snapshots and never touches {@code PortalManager} from the render loop.</p>
 */
public record PortalSnapshot(UUID id, String name, ResourceKey<Level> dimension,
                             PortalPlane plane, UUID linkedPortalId) {
    public boolean linked() {
        return linkedPortalId != null;
    }

    public PortalSnapshot withLinkedPortalId(UUID linked) {
        return new PortalSnapshot(id, name, dimension, plane, linked);
    }
}
