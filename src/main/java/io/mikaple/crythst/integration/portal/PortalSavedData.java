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
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent storage for the static portal registry.
 *
 * <p>Data is stored once per world in the overworld's {@code SavedDataStorage}
 * (normally {@code world/data/crythst_portals.dat}). The file contains a stable
 * UUID, name, dimension, plane (origin/normal/right/width/height) and linked
 * UUID for every portal. Target data is resolved from the linked portal's own
 * persisted record; a link to a missing portal is discarded on load.</p>
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

    private static final int VERSION = 1;
    private static final String KEY_VERSION = "version";
    private static final String KEY_PORTALS = "portals";

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
        final ListTag list = data.getListOrEmpty(KEY_PORTALS);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompoundOrEmpty(i);
            try {
                final StoredPortal portal = readEntry(entry);
                if (portal != null) {
                    result.add(portal);
                }
            } catch (RuntimeException error) {
                Crythst.LOGGER.warn("Skipping invalid saved portal entry #{}", i, error);
            }
        }
        return result;
    }

    private static StoredPortal readEntry(CompoundTag entry) {
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

        final PortalPlane plane = new PortalPlane(origin, normal, right, width, height);
        final UUID linked = parseUuid(entry.getStringOr("linked", ""));
        return new StoredPortal(id, name, dimension, plane, linked);
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
            if (portal.linkedId() != null) {
                entry.putString("linked", portal.linkedId().toString());
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
     */
    public record StoredPortal(UUID id, String name, ResourceKey<Level> dimension,
                               PortalPlane plane, UUID linkedId) {
    }
}
