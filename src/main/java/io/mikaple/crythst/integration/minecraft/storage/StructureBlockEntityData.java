package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Side table of persisted block-entity NBT for structures that are no longer
 * backed by a live {@code BlockVoxelVolume} (for example after a restore, when
 * the editable volume is a {@code MutableVoxelVolume}).
 *
 * <p>Without this table, the next save of a restored structure would capture an
 * empty block-entity map from the mutable volume and overwrite the good data
 * from the previous save.</p>
 */
public final class StructureBlockEntityData {
    private static final Map<PhysicsStructure, Map<BlockPos, CompoundTag>> DATA = new WeakHashMap<>();

    private StructureBlockEntityData() {
    }

    public static synchronized Map<BlockPos, CompoundTag> get(PhysicsStructure structure) {
        final Map<BlockPos, CompoundTag> data = DATA.get(structure);
        return data == null ? Map.of() : copy(data);
    }

    public static synchronized void put(PhysicsStructure structure, Map<BlockPos, CompoundTag> data) {
        if (data == null || data.isEmpty()) {
            DATA.remove(structure);
            return;
        }
        DATA.put(structure, copy(data));
    }

    public static synchronized void remove(PhysicsStructure structure) {
        DATA.remove(structure);
    }

    private static Map<BlockPos, CompoundTag> copy(Map<BlockPos, CompoundTag> source) {
        final Map<BlockPos, CompoundTag> result = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, CompoundTag> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().immutable(), entry.getValue().copy());
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
