package io.mikaple.crythst.network.structure;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One block-entity entry inside a structure.
 *
 * <p>{@code data} contains the block-entity NBT as saved on the server. A
 * {@code null} tag means "keep the client's existing NBT and only update the
 * transient animation state"; this is used for chest opener counts so opening
 * a chest does not resend its whole inventory.</p>
 *
 * @param pos       structure-local voxel (relative to the bounds minimum)
 * @param data      full block-entity NBT, or {@code null} for an open-count-only update
 * @param openCount chest opener count, or {@code -1} when not a chest / unknown
 */
public record StructureBlockEntity(
        StructureVoxelPos pos,
        @Nullable CompoundTag data,
        int openCount
) {
    public StructureBlockEntity {
        pos = Objects.requireNonNull(pos, "pos");
        data = data == null ? null : data.copy();
        if (openCount < -1) {
            throw new IllegalArgumentException("openCount must be >= -1: " + openCount);
        }
    }

    /** Defensive copy; {@code null} for animation-only updates. */
    @Override
    public @Nullable CompoundTag data() {
        return data == null ? null : data.copy();
    }

    /** Encoded NBT payload size, excluding the small per-entry header. */
    public int nbtBytes() {
        return data == null ? 0 : data.sizeInBytes();
    }
}
