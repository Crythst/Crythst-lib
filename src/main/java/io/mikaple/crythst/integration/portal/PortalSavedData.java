package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.Crythst;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.joml.Vector2d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent storage for the static portal registry.
 *
 * <p>Data is stored once per world in the overworld's {@code SavedDataStorage}
 * (normally {@code world/data/crythst_portals.dat}). Version 2 adds an optional
 * polygon outline; version-1 files without an outline load as the plane
 * rectangle. Target data is resolved from the linked portal's own persisted
 * record; a link to a missing portal is discarded on load.</p>
 *
 * <p>Client capture textures are never persisted: they are recreated from the
 * loaded portal planes on demand.</p>
 */
public final class PortalSavedData extends SavedData {
    /** Saved-data id: {@code crythst:portals}. */
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portals");
    /** Saved-data type, registered lazily by the storage when first requested. */
    public static final SavedDataType<PortalSavedData> TYPE = new SavedDataType<>(
            ID,
            PortalSavedData::new,
            CompoundTag.CODEC.xmap(PortalSavedData::new, PortalSavedData::tag),
            DataFixTypes.LEVEL);

    private static final int VERSION = 4;
    private static final String KEY_VERSION = "version";
    private static final String KEY_PORTALS = "portals";
    private static final String KEY_OUTLINE = "outline";
    private static final String KEY_ANCHOR = "anchor";
    private static final String KEY_CARRIER = "carrier";
    private static final int MAX_OUTLINE_POINTS = 64;

    private CompoundTag data;

    public PortalSavedData() {
        this(new CompoundTag());
    }

    public PortalSavedData(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data;
    }

    /** Immutable copy for the saved-data codec. */
    public CompoundTag tag() {
        return data.copy();
    }

    /** Replaces the stored tag and marks this data dirty. */
    public void setTag(CompoundTag tag) {
        this.data = tag == null ? new CompoundTag() : tag;
        setDirty();
    }

    /** Reads and validates every persisted portal record. */
    public List<StoredPortal> readPortals() {
        final List<StoredPortal> result = new ArrayList<>();
        final boolean legacyDirection = data.getIntOr(KEY_VERSION, 1) < VERSION;
        final ListTag list = data.getListOrEmpty(KEY_PORTALS);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompoundOrEmpty(i);
            try {
                final StoredPortal portal = readEntry(entry, legacyDirection);
                if (portal != null) {
                    result.add(portal);
                }
            } catch (RuntimeException error) {
                Crythst.LOGGER.warn("Skipping invalid saved portal entry #{}", i, error);
            }
        }
        return result;
    }

    private static StoredPortal readEntry(CompoundTag entry, boolean legacyDirection) {
        final UUID id = parseUuid(entry.getStringOr("id", ""));
        if (id == null) {
            return null;
        }
        final String name = entry.getStringOr("name", "");
        if (name.isBlank()) {
            return null;
        }
        final Identifier dimensionId = Identifier.tryParse(entry.getStringOr("dimension", ""));
        if (dimensionId == null) {
            return null;
        }
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);

        final Vector3d origin = readVector(entry, "o");
        final Vector3d normal = readVector(entry, "n");
        final Vector3d right = readVector(entry, "r");
        if (!isFinite(origin) || !isFinite(normal) || !isFinite(right)) {
            return null;
        }
        final double width = entry.getDoubleOr("width", Double.NaN);
        final double height = entry.getDoubleOr("height", Double.NaN);
        if (!Double.isFinite(width) || !Double.isFinite(height)) {
            return null;
        }

        List<Vector2d> outline = readOutline(entry);
        if (legacyDirection) {
            // The old normal faced the creator. Preserve up and world-space
            // outline vertices while changing to their original look direction.
            normal.negate();
            right.negate();
            outline = outline.stream().map(point -> new Vector2d(-point.x, point.y)).toList();
        }
        final PortalPlane plane = new PortalPlane(origin, normal, right, width, height);
        final UUID linked = parseUuid(entry.getStringOr("linked", ""));
        final PortalAnchor anchor = legacyDirection ? null : readAnchor(entry);
        return new StoredPortal(id, name, dimension, plane, linked, outline, anchor);
    }

    /**
     * Reads the optional moving-carrier binding. Version-4 files written by a
     * static-only server have no {@code anchor} tag at all.
     */
    private static PortalAnchor readAnchor(CompoundTag entry) {
        if (!entry.contains(KEY_ANCHOR)) {
            return null;
        }
        final CompoundTag anchor = entry.getCompoundOrEmpty(KEY_ANCHOR);
        final long carrier = anchor.getLongOr(KEY_CARRIER, 0L);
        if (carrier <= 0L) {
            return null;
        }
        final Vector3d origin = readVector(anchor, "o");
        final Vector3d normal = readVector(anchor, "n");
        final Vector3d right = readVector(anchor, "r");
        final double width = anchor.getDoubleOr("width", Double.NaN);
        final double height = anchor.getDoubleOr("height", Double.NaN);
        if (!isFinite(origin) || !isFinite(normal) || !isFinite(right)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            return null;
        }
        try {
            return new PortalAnchor(carrier,
                    new PortalPlane(origin, normal, right, width, height));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static List<Vector2d> readOutline(CompoundTag entry) {
        final ListTag list = entry.getListOrEmpty(KEY_OUTLINE);
        if (list.isEmpty()) {
            return List.of();
        }
        if (list.size() < 3 || list.size() > MAX_OUTLINE_POINTS) {
            return List.of();
        }
        final List<Vector2d> outline = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag point = list.getCompoundOrEmpty(i);
            final double x = point.getDoubleOr("x", Double.NaN);
            final double y = point.getDoubleOr("y", Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return List.of();
            }
            outline.add(new Vector2d(x, y));
        }
        return List.copyOf(outline);
    }

    private static Vector3d readVector(CompoundTag entry, String prefix) {
        return new Vector3d(
                entry.getDoubleOr(prefix + "x", Double.NaN),
                entry.getDoubleOr(prefix + "y", Double.NaN),
                entry.getDoubleOr(prefix + "z", Double.NaN));
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** Serialises the given records into a fresh NBT tag. */
    public static CompoundTag write(List<StoredPortal> portals) {
        final CompoundTag root = new CompoundTag();
        root.putInt(KEY_VERSION, VERSION);
        final ListTag list = new ListTag();
        for (StoredPortal portal : portals) {
            final CompoundTag entry = new CompoundTag();
            entry.putString("id", portal.id().toString());
            entry.putString("name", portal.name());
            entry.putString("dimension", portal.dimension().identifier().toString());
            writeVector(entry, "o", portal.plane().origin());
            writeVector(entry, "n", portal.plane().normal());
            writeVector(entry, "r", portal.plane().right());
            entry.putDouble("width", portal.plane().width());
            entry.putDouble("height", portal.plane().height());
            if (!portal.outline().isEmpty()) {
                final ListTag outline = new ListTag();
                for (Vector2d point : portal.outline()) {
                    final CompoundTag pointTag = new CompoundTag();
                    pointTag.putDouble("x", point.x);
                    pointTag.putDouble("y", point.y);
                    outline.add(pointTag);
                }
                entry.put(KEY_OUTLINE, outline);
            }
            if (portal.linkedId() != null) {
                entry.putString("linked", portal.linkedId().toString());
            }
            if (portal.anchor() != null) {
                final PortalAnchor anchor = portal.anchor();
                final PortalPlane local = anchor.localPlane();
                final CompoundTag anchorTag = new CompoundTag();
                anchorTag.putLong(KEY_CARRIER, anchor.carrierBodyId());
                writeVector(anchorTag, "o", local.origin());
                writeVector(anchorTag, "n", local.normal());
                writeVector(anchorTag, "r", local.right());
                anchorTag.putDouble("width", local.width());
                anchorTag.putDouble("height", local.height());
                entry.put(KEY_ANCHOR, anchorTag);
            }
            list.add(entry);
        }
        root.put(KEY_PORTALS, list);
        return root;
    }

    private static void writeVector(CompoundTag entry, String prefix, Vector3d vector) {
        entry.putDouble(prefix + "x", vector.x);
        entry.putDouble(prefix + "y", vector.y);
        entry.putDouble(prefix + "z", vector.z);
    }

    /**
     * One persisted portal definition.
     *
     * <p>{@code anchor} is the optional moving-carrier binding. It lives in the
     * saved data instead of being recomputed so a restart reattaches the portal
     * to the same physics body even if the carrier moved while the world was
     * unloaded.</p>
     */
    public record StoredPortal(UUID id, String name, ResourceKey<Level> dimension,
                               PortalPlane plane, UUID linkedId, List<Vector2d> outline,
                               PortalAnchor anchor) {
        public StoredPortal {
            outline = outline == null ? List.of() : List.copyOf(outline);
        }

        /** Version-1/2 compatible constructor: rectangular static opening. */
        public StoredPortal(UUID id, String name, ResourceKey<Level> dimension,
                            PortalPlane plane, UUID linkedId) {
            this(id, name, dimension, plane, linkedId, List.of(), null);
        }

        /** Version-3 compatible constructor: shaped static opening. */
        public StoredPortal(UUID id, String name, ResourceKey<Level> dimension,
                            PortalPlane plane, UUID linkedId, List<Vector2d> outline) {
            this(id, name, dimension, plane, linkedId, outline, null);
        }

        /** Applies the persisted outline when one exists. */
        public PortalShape shape() {
            return outline.isEmpty() ? PortalShape.rectangle(plane)
                    : PortalShape.polygon(plane, outline);
        }
    }
}
