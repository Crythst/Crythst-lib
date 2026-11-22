package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.api.physics.constraint.StructureConstraintConfiguration;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One persisted constraint record.
 *
 * <p>A constraint is identified by its own stable UUID and references its
 * structures by the stable UUIDs used in {@link StructureSavedData}. A missing
 * structure-B UUID means the joint is world-anchored. Frames, anchors and axes
 * inside the configuration are structure-local (or world-space for side B of a
 * world-anchored joint), so no live body ids or centre-of-mass offsets are
 * persisted.</p>
 */
public record SavedConstraint(
        UUID id,
        UUID structureA,
        @Nullable UUID structureB,
        boolean enabled,
        StructureConstraintConfiguration configuration
) {
    public static final int FORMAT_VERSION = 1;

    public SavedConstraint {
        Validate.notNull(id, "id");
        Validate.notNull(structureA, "structureA");
        Validate.notNull(configuration, "configuration");
    }

    /** Whether this joint has no second structure. */
    public boolean worldAnchored() {
        return structureB == null;
    }

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("Id", UUIDUtil.uuidToIntArray(id));
        tag.putIntArray("StructureA", UUIDUtil.uuidToIntArray(structureA));
        if (structureB != null) {
            tag.putIntArray("StructureB", UUIDUtil.uuidToIntArray(structureB));
        }
        tag.putBoolean("Enabled", enabled);
        tag.put("Config", ConstraintConfigCodec.encode(configuration));
        return tag;
    }

    public static SavedConstraint fromTag(CompoundTag tag) {
        Validate.notNull(tag, "tag");
        final UUID id = readUuid(tag, "Id");
        final UUID structureA = readUuid(tag, "StructureA");
        if (structureA == null) {
            throw new IllegalArgumentException("Saved constraint is missing StructureA");
        }
        return new SavedConstraint(
                id == null ? UUID.randomUUID() : id,
                structureA,
                readUuid(tag, "StructureB"),
                tag.getBooleanOr("Enabled", true),
                ConstraintConfigCodec.decode(tag.getCompoundOrEmpty("Config")));
    }

    private static @Nullable UUID readUuid(CompoundTag tag, String key) {
        final int[] value = tag.getIntArray(key).orElse(null);
        return value != null && value.length == 4 ? UUIDUtil.uuidFromIntArray(value) : null;
    }
}
