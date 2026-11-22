package io.mikaple.crythst.api.sublevel.tracking;

import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * Persistent world-space tracking point attached to a structure.
 *
 * <p>A point stores both a structure-local position and the world position it
 * had when saved. The local position is authoritative while the structure is
 * live; the saved world position is a fallback for re-binding after a reload
 * when body ids changed. An optional stable structure identity is filled from
 * {@code StructureSavedData} when available.</p>
 *
 * <p><strong>Units:</strong> local and world positions are metres.
 * <strong>Threading:</strong> immutable and safe to read on either side. Create
 * and mutate points through {@link StructureTrackingPoints} on the server
 * thread.</p>
 */
public record StructureTrackingPoint(
        UUID id,
        String name,
        long bodyId,
        @Nullable UUID structureIdentity,
        Vector3d localPosition,
        Vector3d worldPositionAtSave,
        boolean keepLoaded,
        int keepLoadedRadiusChunks
) {
    public static final int MAX_NAME_LENGTH = 128;

    public StructureTrackingPoint {
        if (id == null) {
            throw new NullPointerException("id");
        }
        name = name == null ? "" : name;
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " chars");
        }
        if (localPosition == null) {
            throw new NullPointerException("localPosition");
        }
        localPosition = new Vector3d(localPosition);
        if (!isFinite(localPosition)) {
            throw new IllegalArgumentException("localPosition must be finite: " + localPosition);
        }
        worldPositionAtSave = worldPositionAtSave == null
                ? new Vector3d(localPosition) : new Vector3d(worldPositionAtSave);
        if (!isFinite(worldPositionAtSave)) {
            throw new IllegalArgumentException(
                    "worldPositionAtSave must be finite: " + worldPositionAtSave);
        }
        if (keepLoadedRadiusChunks < 0
                || keepLoadedRadiusChunks > io.mikaple.crythst.api.sublevel.ticket.StructureLoadingTickets.MAX_RADIUS_CHUNKS) {
            throw new IllegalArgumentException("keepLoadedRadiusChunks out of range: "
                    + keepLoadedRadiusChunks);
        }
    }

    private static boolean isFinite(Vector3dc vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    /** Creates a point with a fresh id. */
    public static StructureTrackingPoint create(String name, PhysicsStructure structure,
                                                Vector3dc localPosition) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        final Vector3d local = new Vector3d(localPosition);
        return new StructureTrackingPoint(UUID.randomUUID(), name, structure.body().id(), null,
                local, structure.pose().transformPoint(local), false, 1);
    }

    /** @return a defensive copy of the structure-local position */
    @Override
    public Vector3d localPosition() {
        return new Vector3d(localPosition);
    }

    /** @return a defensive copy of the saved world position */
    @Override
    public Vector3d worldPositionAtSave() {
        return new Vector3d(worldPositionAtSave);
    }

    /** Current world position for a live structure. */
    public Vector3d worldPosition(PhysicsStructure structure) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        return structure.pose().transformPoint(localPosition);
    }

    /** Current world position for a level-like view. */
    public Vector3d worldPosition(StructureLevelView view) {
        if (view == null) {
            throw new NullPointerException("view");
        }
        return view.localToWorld(localPosition, new Vector3d());
    }

    /** Current world position when no live structure is available. */
    public Vector3d worldPosition() {
        return new Vector3d(worldPositionAtSave);
    }

    /** Returns a copy with a different id. */
    public StructureTrackingPoint withId(UUID newId) {
        return new StructureTrackingPoint(newId, name, bodyId, structureIdentity,
                localPosition, worldPositionAtSave, keepLoaded, keepLoadedRadiusChunks);
    }

    /** Returns a copy with a different display name. */
    public StructureTrackingPoint withName(String newName) {
        return new StructureTrackingPoint(id, newName, bodyId, structureIdentity,
                localPosition, worldPositionAtSave, keepLoaded, keepLoadedRadiusChunks);
    }

    /** Returns a copy bound to a body id and optional stable identity. */
    public StructureTrackingPoint withStructure(long newBodyId,
                                                @Nullable UUID newStructureIdentity,
                                                Vector3dc newLocalPosition) {
        return new StructureTrackingPoint(id, name, newBodyId, newStructureIdentity,
                new Vector3d(newLocalPosition), worldPositionAtSave, keepLoaded, keepLoadedRadiusChunks);
    }

    /** Returns a copy with the saved world position updated. */
    public StructureTrackingPoint withSavePosition(Vector3dc newWorldPosition) {
        return new StructureTrackingPoint(id, name, bodyId, structureIdentity,
                localPosition, new Vector3d(newWorldPosition), keepLoaded, keepLoadedRadiusChunks);
    }

    /**
     * Returns a copy with persistence loading enabled or disabled.
     *
     * @param enabled      whether to keep host chunks loaded
     * @param radiusChunks extra chunk radius
     */
    public StructureTrackingPoint withKeepLoaded(boolean enabled, int radiusChunks) {
        return new StructureTrackingPoint(id, name, bodyId, structureIdentity,
                localPosition, worldPositionAtSave, enabled, radiusChunks);
    }

    /** Serialises this point to NBT. */
    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("Id", UUIDUtil.uuidToIntArray(id));
        tag.putString("Name", name);
        tag.putLong("BodyId", bodyId);
        if (structureIdentity != null) {
            tag.putIntArray("StructureId", UUIDUtil.uuidToIntArray(structureIdentity));
        }
        tag.putDouble("LocalX", localPosition.x);
        tag.putDouble("LocalY", localPosition.y);
        tag.putDouble("LocalZ", localPosition.z);
        tag.putDouble("WorldX", worldPositionAtSave.x);
        tag.putDouble("WorldY", worldPositionAtSave.y);
        tag.putDouble("WorldZ", worldPositionAtSave.z);
        tag.putBoolean("KeepLoaded", keepLoaded);
        tag.putInt("KeepLoadedRadius", keepLoadedRadiusChunks);
        return tag;
    }

    /**
     * Reads a point from NBT.
     *
     * @return the point, or {@code null} when required fields are missing or
     *         invalid
     */
    public static @Nullable StructureTrackingPoint fromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        final UUID id = readUuid(tag, "Id");
        if (id == null) {
            return null;
        }
        final UUID structureIdentity = readUuid(tag, "StructureId");
        try {
            return new StructureTrackingPoint(id, tag.getStringOr("Name", ""),
                    tag.getLongOr("BodyId", 0L), structureIdentity,
                    new Vector3d(tag.getDoubleOr("LocalX", 0.0),
                            tag.getDoubleOr("LocalY", 0.0),
                            tag.getDoubleOr("LocalZ", 0.0)),
                    new Vector3d(tag.getDoubleOr("WorldX", 0.0),
                            tag.getDoubleOr("WorldY", 0.0),
                            tag.getDoubleOr("WorldZ", 0.0)),
                    tag.getBooleanOr("KeepLoaded", false),
                    tag.getIntOr("KeepLoadedRadius", 1));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static @Nullable UUID readUuid(CompoundTag tag, String key) {
        final int[] raw = tag.getIntArray(key).orElse(null);
        return raw != null && raw.length == 4 ? UUIDUtil.uuidFromIntArray(raw) : null;
    }
}
