package io.mikaple.crythst.network.structure;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure diffing helper for block-entity maps.
 *
 * <p>The server keeps the last successfully published map per body. On every
 * source change it computes the minimal dirty set: changed entries carry NBT
 * only when the NBT actually changed, while a chest opening/closing only sends
 * the opener count. Removed positions are returned separately so the client can
 * drop stale block entities after a break or split.</p>
 */
public final class StructureBlockEntityDelta {
    private static final Comparator<StructureVoxelPos> POSITION_ORDER =
            Comparator.comparingInt(StructureVoxelPos::x)
                    .thenComparingInt(StructureVoxelPos::y)
                    .thenComparingInt(StructureVoxelPos::z);

    private StructureBlockEntityDelta() {
    }

    /**
     * Immutable server-side value for one block entity.
     *
     * @param data      full NBT, never {@code null}
     * @param openCount chest opener count, or {@code -1}
     */
    public record Value(CompoundTag data, int openCount) {
        public Value {
            data = Objects.requireNonNull(data, "data").copy();
            if (openCount < -1) {
                throw new IllegalArgumentException("openCount must be >= -1: " + openCount);
            }
        }

        @Override
        public CompoundTag data() {
            return data.copy();
        }

        public Value withOpenCount(int count) {
            return new Value(data, count);
        }
    }

    /** Minimal dirty set between two published maps. */
    public record Result(List<StructureBlockEntity> changed,
                         List<StructureVoxelPos> removed) {
        public Result {
            changed = List.copyOf(changed);
            removed = List.copyOf(removed);
        }

        public boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }
    }

    /**
     * Computes additions, NBT changes, animation-only changes and removals.
     * Both input maps are read-only and may be empty.
     */
    public static Result compute(Map<StructureVoxelPos, Value> before,
                                 Map<StructureVoxelPos, Value> after) {
        final Map<StructureVoxelPos, Value> previous = before == null ? Map.of() : before;
        final Map<StructureVoxelPos, Value> current = after == null ? Map.of() : after;

        final List<StructureBlockEntity> changed = new ArrayList<>();
        final List<StructureVoxelPos> positions = new ArrayList<>(current.keySet());
        positions.sort(POSITION_ORDER);
        for (StructureVoxelPos pos : positions) {
            final Value now = current.get(pos);
            final Value old = previous.get(pos);
            if (old == null) {
                changed.add(new StructureBlockEntity(pos, now.data(), now.openCount()));
                continue;
            }
            final boolean nbtChanged = !old.data().equals(now.data());
            final boolean animationChanged = old.openCount() != now.openCount();
            if (!nbtChanged && !animationChanged) {
                continue;
            }
            changed.add(new StructureBlockEntity(pos,
                    nbtChanged ? now.data() : null,
                    now.openCount()));
        }

        final List<StructureVoxelPos> removed = new ArrayList<>();
        for (StructureVoxelPos pos : previous.keySet()) {
            if (!current.containsKey(pos)) {
                removed.add(pos);
            }
        }
        removed.sort(POSITION_ORDER);
        return new Result(changed, removed);
    }

    /** Full map of entries for a replacement update. */
    public static List<StructureBlockEntity> fullEntries(Map<StructureVoxelPos, Value> values) {
        final List<StructureVoxelPos> positions = new ArrayList<>(
                values == null ? Collections.emptySet() : values.keySet());
        positions.sort(POSITION_ORDER);
        final List<StructureBlockEntity> entries = new ArrayList<>(positions.size());
        for (StructureVoxelPos pos : positions) {
            final Value value = values.get(pos);
            entries.add(new StructureBlockEntity(pos, value.data(), value.openCount()));
        }
        return entries;
    }

    /** Copies a map into the ordered mutable form used by server state. */
    public static Map<StructureVoxelPos, Value> copyOf(Map<StructureVoxelPos, Value> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        final Map<StructureVoxelPos, Value> copy = new LinkedHashMap<>();
        final List<StructureVoxelPos> positions = new ArrayList<>(source.keySet());
        positions.sort(POSITION_ORDER);
        for (StructureVoxelPos pos : positions) {
            final Value value = source.get(pos);
            if (value != null) {
                copy.put(pos, new Value(value.data(), value.openCount()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
