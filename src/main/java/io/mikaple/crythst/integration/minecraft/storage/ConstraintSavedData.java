package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.api.physics.constraint.StructureConstraintConfiguration;
import io.mikaple.crythst.api.physics.constraint.StructureConstraintHandle;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-dimension saved data holding constraints between persisted structures.
 *
 * <p>Records reference structures by the stable UUIDs owned by
 * {@link StructureSavedData}, never by transient Bullet body ids. On load,
 * structures are restored first and constraints are recreated from their saved
 * structure-local frames/anchors. Any record whose structures are missing is
 * dropped instead of reconnecting to an unrelated live body.</p>
 *
 * <p>Only constraints created through the API (which retain a
 * {@link StructureConstraintConfiguration}) are persisted. Raw backend joints
 * without a configuration are skipped.</p>
 */
public final class ConstraintSavedData extends SavedData {
    /** Data file id: {@code <world>/data/crythst/constraints.dat}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "constraints");
    public static final int FORMAT_VERSION = 1;

    public static final SavedDataType<ConstraintSavedData> TYPE = new SavedDataType<>(
            ID,
            ConstraintSavedData::new,
            CompoundTag.CODEC.xmap(ConstraintSavedData::new, ConstraintSavedData::toTag),
            DataFixTypes.SAVED_DATA_STRUCTURE_FEATURE_INDICES);

    private final Map<UUID, CompoundTag> records = new LinkedHashMap<>();
    /** Live backend constraint id -> stable persisted UUID. */
    private final Map<Long, UUID> identities = new LinkedHashMap<>();
    private final Map<UUID, StructureConstraintHandle> live = new LinkedHashMap<>();
    private boolean restored;

    public ConstraintSavedData() {
    }

    private ConstraintSavedData(CompoundTag root) {
        final ListTag constraints = root.getListOrEmpty("Constraints");
        for (int i = 0; i < constraints.size(); i++) {
            final CompoundTag record = constraints.getCompoundOrEmpty(i).copy();
            if (record.isEmpty()) {
                continue;
            }
            final UUID id = readId(record);
            final UUID stable = id == null ? UUID.randomUUID() : id;
            if (id == null) {
                record.putIntArray("Id", UUIDUtil.uuidToIntArray(stable));
            }
            records.put(stable, record);
        }
    }

    public CompoundTag toTag() {
        final CompoundTag root = new CompoundTag();
        root.putInt("Version", FORMAT_VERSION);
        final ListTag constraints = new ListTag();
        for (CompoundTag record : records.values()) {
            constraints.add(record.copy());
        }
        root.put("Constraints", constraints);
        return root;
    }

    public int constraintCount() {
        return records.size();
    }

    public List<UUID> constraintIds() {
        return List.copyOf(records.keySet());
    }

    public boolean restored() {
        return restored;
    }

    /** Live constraints restored by this data object, for diagnostics. */
    public Map<UUID, StructureConstraintHandle> liveConstraints() {
        return Collections.unmodifiableMap(live);
    }

    /**
     * Adds or replaces a record. Primarily useful for tests and import tools.
     */
    public void add(SavedConstraint constraint) {
        Objects.requireNonNull(constraint, "constraint");
        records.put(constraint.id(), constraint.toTag());
        setDirty();
    }

    public void clear() {
        if (records.isEmpty() && live.isEmpty()) {
            return;
        }
        records.clear();
        identities.clear();
        live.clear();
        setDirty();
    }

    /**
     * Recreates saved constraints in the given physics world.
     *
     * <p>Call after {@link StructureSavedData#restore(ServerLevel, LevelPhysics)}
     * so stable structure UUIDs can be resolved. Missing structures cause the
     * record to be dropped. Safe to call more than once; only the first call
     * does work.</p>
     */
    public void restore(ServerLevel level, LevelPhysics physics,
                        StructureSavedData structures) {
        if (restored) {
            return;
        }
        restored = true;
        if (records.isEmpty()) {
            return;
        }
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(physics, "physics");
        final PhysicsPipeline pipeline = physics.apiPipeline();

        boolean dropped = false;
        for (Map.Entry<UUID, CompoundTag> entry : List.copyOf(records.entrySet())) {
            final UUID id = entry.getKey();
            try {
                final SavedConstraint saved = SavedConstraint.fromTag(entry.getValue());
                final PhysicsStructure a = structures == null
                        ? null : structures.liveStructure(saved.structureA());
                final PhysicsStructure b = saved.structureB() == null || structures == null
                        ? null : structures.liveStructure(saved.structureB());
                if (a == null || a.closed()
                        || (saved.structureB() != null && (b == null || b.closed()))) {
                    records.remove(id);
                    dropped = true;
                    Crythst.LOGGER.warn("Dropping dangling Crythst constraint {} in {}: "
                                    + "structure A={} B={}", id, level.dimension().identifier(),
                            saved.structureA(), saved.structureB());
                    continue;
                }
                final StructureConstraintHandle handle = b == null
                        ? pipeline.addConstraint(a, saved.configuration())
                        : pipeline.addConstraint(a, b, saved.configuration());
                if (!saved.enabled()) {
                    handle.setEnabled(false);
                }
                identities.put(handle.id(), id);
                live.put(id, handle);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Could not restore Crythst constraint {} in {}",
                        id, level.dimension().identifier(), t);
            }
        }
        if (dropped) {
            setDirty();
        } else {
            setDirty(false);
        }
    }

    /**
     * Captures constraints whose structures have a stable UUID and which retain
     * an API configuration. Records for removed constraints are dropped.
     */
    public void capture(ServerLevel level, LevelPhysics physics,
                        StructureSavedData structures) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(physics, "physics");
        if (structures == null) {
            return;
        }

        final PhysicsPipeline pipeline = physics.apiPipeline();
        final Map<UUID, CompoundTag> captured = new LinkedHashMap<>();
        final Map<Long, UUID> capturedIdentities = new LinkedHashMap<>();
        final Map<UUID, StructureConstraintHandle> capturedLive = new LinkedHashMap<>();

        for (StructureConstraintHandle handle : pipeline.constraints()) {
            if (!handle.isValid()) {
                continue;
            }
            final StructureConstraintConfiguration configuration = handle.configuration();
            if (configuration == null) {
                continue;
            }
            final PhysicsStructure a = handle.structureA();
            final PhysicsStructure b = handle.structureB();
            if (a == null || a.closed()) {
                continue;
            }
            final UUID structureA = structures.identityOf(a);
            if (structureA == null) {
                continue;
            }
            final UUID structureB = b == null ? null : structures.identityOf(b);
            if (b != null && structureB == null) {
                continue;
            }
            UUID id = identities.get(handle.id());
            if (id == null) {
                id = UUID.randomUUID();
            }
            try {
                final SavedConstraint saved = new SavedConstraint(id, structureA,
                        structureB, handle.enabled(), configuration);
                captured.put(id, saved.toTag());
                capturedIdentities.put(handle.id(), id);
                capturedLive.put(id, handle);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Could not capture Crythst constraint #{} in {}",
                        handle.id(), level.dimension().identifier(), t);
                final UUID previousId = identities.get(handle.id());
                final CompoundTag previous = previousId == null ? null : records.get(previousId);
                if (previousId != null && previous != null) {
                    captured.put(previousId, previous.copy());
                    capturedIdentities.put(handle.id(), previousId);
                }
            }
        }

        // Keep identities for constraints that were successfully captured and
        // forget removed ones; this keeps their persisted UUIDs stable.
        identities.clear();
        identities.putAll(capturedIdentities);
        live.clear();
        live.putAll(capturedLive);

        if (!captured.equals(records)) {
            records.clear();
            records.putAll(captured);
            setDirty();
        }
    }

    /** All records, unmodifiable, for diagnostics. */
    public List<CompoundTag> records() {
        final List<CompoundTag> copy = new ArrayList<>(records.size());
        for (CompoundTag record : records.values()) {
            copy.add(record.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    private static UUID readId(CompoundTag record) {
        final int[] id = record.getIntArray("Id").orElse(null);
        return id != null && id.length == 4 ? UUIDUtil.uuidFromIntArray(id) : null;
    }
}
