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
 * snapshots cheap and safe to share with the client renderer. Persistence and
 * networking are intentionally outside this first milestone.</p>
 */
public final class Portal {
    private final UUID id;
    private final String name;
    private final ResourceKey<Level> dimension;
    private final PortalPlane plane;
    private volatile UUID linkedPortalId;

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalPlane plane) {
        this(id, name, dimension, plane, null);
    }

    public Portal(UUID id, String name, ResourceKey<Level> dimension, PortalPlane plane,
                  UUID linkedPortalId) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = validateName(name);
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.plane = Objects.requireNonNull(plane, "plane");
        this.linkedPortalId = linkedPortalId;
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

    /** Linked portal id, or {@code null} when unlinked. */
    public UUID linkedPortalId() {
        return linkedPortalId;
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
        return new PortalSnapshot(id, name, dimension, plane, linkedPortalId);
    }

    @Override
    public String toString() {
        return "Portal[" + name + " " + id + " " + dimension.identifier() + " " + plane + "]";
    }
}
