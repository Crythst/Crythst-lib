package io.mikaple.crythst.client.network;

import io.mikaple.crythst.network.structure.StructureVoxelPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

/**
 * Immutable client view of one structure block entity.
 *
 * <p>The NBT is copied when the snapshot is built and returned as a defensive
 * copy to renderers, so the render thread cannot mutate cache state shared with
 * packet handling.</p>
 */
public final class ClientBlockEntity {
    private final StructureVoxelPos pos;
    private final CompoundTag data;
    private final int openCount;

    ClientBlockEntity(StructureVoxelPos pos, CompoundTag data, int openCount) {
        this.pos = Objects.requireNonNull(pos, "pos");
        this.data = data == null ? new CompoundTag() : data.copy();
        this.openCount = openCount;
    }

    /** Structure-local voxel, relative to the structure bounds minimum. */
    public StructureVoxelPos pos() {
        return pos;
    }

    public int x() {
        return pos.x();
    }

    public int y() {
        return pos.y();
    }

    public int z() {
        return pos.z();
    }

    /** Defensive copy of the block-entity NBT. */
    public CompoundTag data() {
        return data.copy();
    }

    /** Chest opener count, or {@code -1} when the block entity is not animated. */
    public int openCount() {
        return openCount;
    }

    public long packedPos() {
        return pos.packed();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClientBlockEntity that)) {
            return false;
        }
        return openCount == that.openCount
                && pos.equals(that.pos)
                && data.equals(that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, data, openCount);
    }
}
