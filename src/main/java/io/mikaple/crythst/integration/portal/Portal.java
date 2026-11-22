package io.mikaple.crythst.integration.portal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/**
 * One static portal definition.
 *
 * <p>A portal has a stable UUID, a unique human-readable name, the dimension
 * it lives in and an oriented rectangular plane. Linking is symmetric for the
 * first version: both portals store each other's id. The actual transform is
 * derived on demand by {@link PortalTransform#between(PortalPlane, PortalPlane)}
 * so there is no stale matrix to keep in sync.</p>
 *
 * <p>Instances are immutable apart from the volatile link field, which makes
 * snapshots cheap and safe to share with the client renderer. Persistence is
 * handled by {@link PortalSavedData}; multiplayer clients receive immutable
 * snapshots through {@code PortalSyncPayload}.</p>
 */
public final class Portal {
    private final UUID id;
    private final String name;
    private final ResourceKey<Level> dimension;
    private final PortalPlane plane;
    private final PortalShape shape;
    private final PortalAnchor anchor;
    private volatile UUID linkedPortalId;

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalPlane plane) {
        this(id, name, dimension, plane, null);
    }

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalPlane plane,
                  UUID linkedPortalId) {
        this(id, name, dimension, plane, PortalShape.rectangle(plane), linkedPortalId);
    }

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalShape shape,
                  UUID linkedPortalId) {
        this(id, name, dimension, shape.plane(), shape, linkedPortalId, null);
    }

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalShape shape,
                  UUID linkedPortalId, PortalAnchor anchor) {
        this(id, name, dimension, shape.plane(), shape, linkedPortalId, anchor);
    }

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalPlane plane,
                  PortalShape shape, UUID linkedPortalId) {
        this(id, name, dimension, plane, shape, linkedPortalId, null);
    }

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalPlane plane,
                  PortalShape shape, UUID linkedPortalId, PortalAnchor anchor) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = validateName(name);
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.plane = Objects.requireNonNull(plane, "plane");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.linkedPortalId = linkedPortalId;
        this.anchor = anchor;
    }

    private static String validateName(String name) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Portal name must not be empty");
        }
        if (trimmed.length() > 48) {
            throw new IllegalArgumentException("Portal name is too long (max 48 characters)");
        }
        return trimmed;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public PortalPlane plane() {
        return plane;
    }

    /** Traversable opening (rectangle or polygon) on {@link #plane()}. */
    public PortalShape shape() {
        return shape;
    }

    /** Linked portal id, or {@code null} when unlinked. */
    public UUID linkedPortalId() {
        return linkedPortalId;
    }

    /**
     * The moving carrier of this portal, or {@code null} for a static portal.
     *
     * <p>When an anchor is present {@link #plane()} is only the pose the portal
     * was last published with; the live world plane has to be sampled from the
     * carrier through {@link PortalAnchor#worldPlane}. Use
     * {@code PortalManager#planeOf(Portal)} on the server and the resolved
     * snapshot on the client.</p>
     */
    public PortalAnchor anchor() {
        return anchor;
    }

    /** True when this portal rides on a moving physics structure. */
    public boolean attached() {
        return anchor != null;
    }

    public boolean linked() {
        return linkedPortalId != null;
    }

    public void linkTo(UUID other) {
        this.linkedPortalId = other;
    }

    public void unlink() {
        this.linkedPortalId = null;
    }

    public PortalSnapshot snapshot() {
        return PortalSnapshot.of(this, null);
    }

    @Override
    public String toString() {
        return "Portal[" + name + " " + id + " " + dimension.identifier() + " " + plane + "]";
    }
}
