package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Binary layout shared by the snapshot and pose payloads.
 *
 * <p>VarInts keep the common case compact: a stone structure with fewer than
 * 128 palette entries costs roughly one byte per solid/empty voxel. The exact
 * size is estimated by {@link #estimateBytes(StructureSnapshot)} before the
 * server decides whether a structure fits in a packet.</p>
 */
final class StructureSnapshotCodec {
    private StructureSnapshotCodec() {
    }

    static void write(FriendlyByteBuf buf, StructureSnapshot snapshot) {
        buf.writeVarLong(snapshot.bodyId());
        writePose(buf, snapshot.pose());

        final IntBounds3 bounds = snapshot.bounds();
        buf.writeVarInt(bounds.minX());
        buf.writeVarInt(bounds.minY());
        buf.writeVarInt(bounds.minZ());
        buf.writeVarInt(bounds.maxX());
        buf.writeVarInt(bounds.maxY());
        buf.writeVarInt(bounds.maxZ());

        final int[] palette = snapshot.palette();
        buf.writeVarInt(palette.length);
        for (int stateId : palette) {
            buf.writeVarInt(stateId);
        }

        final int[] cells = snapshot.cells();
        buf.writeVarInt(cells.length);
        for (int cell : cells) {
            buf.writeVarInt(cell);
        }

        buf.writeDouble(snapshot.blockScale());
        buf.writeDouble(snapshot.massScale());
        buf.writeDouble(snapshot.colliderMass());
        final Vector3d centerOfMass = snapshot.centerOfMass();
        buf.writeDouble(centerOfMass.x);
        buf.writeDouble(centerOfMass.y);
        buf.writeDouble(centerOfMass.z);
    }

    static StructureSnapshot read(FriendlyByteBuf buf) {
        final long bodyId = buf.readVarLong();
        final Transform3d pose = readPose(buf);

        final IntBounds3 bounds;
        try {
            bounds = new IntBounds3(
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure bounds", e);
        }

        final long volume = bounds.volume();
        if (volume > StructureSyncLimits.MAX_CELLS) {
            throw new DecoderException("Structure has " + volume + " voxels, limit is "
                    + StructureSyncLimits.MAX_CELLS);
        }

        final int paletteSize = buf.readVarInt();
        if (paletteSize < 0 || paletteSize > StructureSyncLimits.MAX_PALETTE) {
            throw new DecoderException("Invalid palette size " + paletteSize);
        }
        final int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = buf.readVarInt();
        }

        final int cellCount = buf.readVarInt();
        if (cellCount < 0 || cellCount != (int) volume) {
            throw new DecoderException("Cell count " + cellCount
                    + " does not match bounds volume " + volume);
        }
        final int[] cells = new int[cellCount];
        for (int i = 0; i < cellCount; i++) {
            final int cell = buf.readVarInt();
            if (cell < 0 || cell > paletteSize) {
                throw new DecoderException("Cell index " + cell
                        + " outside palette range 0.." + paletteSize);
            }
            cells[i] = cell;
        }

        final double blockScale = buf.readDouble();
        final double massScale = buf.readDouble();
        final double colliderMass = buf.readDouble();
        final Vector3d centerOfMass = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());

        try {
            return new StructureSnapshot(bodyId, pose, bounds, palette, cells,
                    blockScale, massScale, colliderMass, centerOfMass);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure snapshot", e);
        }
    }

    static void writePose(FriendlyByteBuf buf, Transform3d pose) {
        buf.writeDouble(pose.position().x);
        buf.writeDouble(pose.position().y);
        buf.writeDouble(pose.position().z);
        buf.writeDouble(pose.rotation().x);
        buf.writeDouble(pose.rotation().y);
        buf.writeDouble(pose.rotation().z);
        buf.writeDouble(pose.rotation().w);
    }

    static Transform3d readPose(FriendlyByteBuf buf) {
        final Vector3d position = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        final Quaterniond rotation = new Quaterniond(
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        try {
            return Transform3d.of(position, rotation);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid structure pose", e);
        }
    }

    /**
     * Conservative wire-size estimate. It counts exact VarInt widths, pose
     * doubles and the payload's per-structure header.
     */
    static int estimateBytes(StructureSnapshot snapshot) {
        long size = 10L + 56L + 6L * 5L; // body id, pose, bounds
        size += 5L; // palette length
        for (int stateId : snapshot.palette()) {
            size += varIntSize(stateId);
        }
        size += 5L; // cell count
        for (int cell : snapshot.cells()) {
            size += varIntSize(cell);
        }
        size += 40L; // block scale, mass scale, collider mass, centre of mass
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            remaining >>>= 7;
            size++;
        }
        return size;
    }
}
