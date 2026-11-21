package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePayloadCodecTest {
    @Test
    void snapshotPayloadRoundTrips() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(12.5, -3.25, 0.125),
                new Quaterniond().rotateY(Math.toRadians(32.0)).rotateX(Math.toRadians(-7.0)));
        final StructureSnapshot snapshot = new StructureSnapshot(
                77L,
                pose,
                new IntBounds3(-1, 0, 5, 1, 2, 7),
                new int[]{3, 17, 4096},
                new int[]{0, 1, 2, 0, 3, 1, 0, 2},
                0.5,
                2.0,
                1234.5,
                new Vector3d(0.25, 1.0, -0.5));
        final StructureSnapshotPayload payload = new StructureSnapshotPayload(
                1234L, Identifier.fromNamespaceAndPath("minecraft", "overworld"),
                List.of(snapshot), 0, 1, true);

        final StructureSnapshotPayload decoded = roundTrip(StructureSnapshotPayload.STREAM_CODEC, payload);

        assertEquals(1234L, decoded.syncId());
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "overworld"), decoded.dimension());
        assertEquals(0, decoded.batchIndex());
        assertEquals(1, decoded.batchCount());
        assertTrue(decoded.replaceAll());
        assertEquals(1, decoded.structures().size());

        final StructureSnapshot actual = decoded.structures().getFirst();
        assertEquals(snapshot.bodyId(), actual.bodyId());
        assertEquals(snapshot.bounds(), actual.bounds());
        assertArrayEquals(snapshot.palette(), actual.palette());
        assertArrayEquals(snapshot.cells(), actual.cells());
        assertEquals(snapshot.blockScale(), actual.blockScale(), 0.0);
        assertEquals(snapshot.massScale(), actual.massScale(), 0.0);
        assertEquals(snapshot.colliderMass(), actual.colliderMass(), 0.0);
        assertEquals(snapshot.centerOfMass().x, actual.centerOfMass().x, 0.0);
        assertEquals(snapshot.centerOfMass().y, actual.centerOfMass().y, 0.0);
        assertEquals(snapshot.centerOfMass().z, actual.centerOfMass().z, 0.0);

        assertEquals(pose.position().x, actual.pose().position().x, 1.0e-12);
        assertEquals(pose.position().y, actual.pose().position().y, 1.0e-12);
        assertEquals(pose.position().z, actual.pose().position().z, 1.0e-12);
        assertEquals(pose.rotation().x, actual.pose().rotation().x, 1.0e-12);
        assertEquals(pose.rotation().y, actual.pose().rotation().y, 1.0e-12);
        assertEquals(pose.rotation().z, actual.pose().rotation().z, 1.0e-12);
        assertEquals(pose.rotation().w, actual.pose().rotation().w, 1.0e-12);

        assertEquals(0, actual.cellAt(-1, 0, 5));
        assertEquals(2, actual.cellAt(0, 1, 6));
        assertTrue(actual.isSolid(0, 1, 6));
    }

    @Test
    void emptyFullSyncRoundTrips() {
        final StructureSnapshotPayload decoded = roundTrip(
                StructureSnapshotPayload.STREAM_CODEC,
                new StructureSnapshotPayload(9L,
                        Identifier.fromNamespaceAndPath("minecraft", "the_nether"),
                        List.of(), 0, 1, true));
        assertEquals(9L, decoded.syncId());
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "the_nether"), decoded.dimension());
        assertTrue(decoded.structures().isEmpty());
        assertTrue(decoded.replaceAll());
    }

    @Test
    void posePayloadRoundTrips() {
        final StructurePosePayload payload = new StructurePosePayload(77L, List.of(
                new StructurePosePayload.PoseUpdate(1L, Transform3d.of(new Vector3d(1, 2, 3))),
                new StructurePosePayload.PoseUpdate(9001L, Transform3d.of(
                        new Vector3d(-4.5, 0.0, 8.25),
                        new Quaterniond().rotateZ(Math.toRadians(45.0))))));

        final StructurePosePayload decoded = roundTrip(StructurePosePayload.STREAM_CODEC, payload);

        assertEquals(77L, decoded.syncId());
        assertEquals(2, decoded.updates().size());
        assertEquals(1L, decoded.updates().get(0).bodyId());
        assertEquals(9001L, decoded.updates().get(1).bodyId());
        assertEquals(-4.5, decoded.updates().get(1).pose().position().x, 1.0e-12);
        assertEquals(8.25, decoded.updates().get(1).pose().position().z, 1.0e-12);
    }

    @Test
    void removePayloadRoundTrips() {
        final StructureRemovePayload decoded = roundTrip(
                StructureRemovePayload.STREAM_CODEC,
                new StructureRemovePayload(55L, new long[]{2L, 4L, 9001L}));
        assertEquals(55L, decoded.syncId());
        assertArrayEquals(new long[]{2L, 4L, 9001L}, decoded.bodyIds());
    }

    @Test
    void requestPayloadRoundTrips() {
        final CustomPacketPayload decoded = roundTrip(
                StructureRequestPayload.STREAM_CODEC,
                new StructureRequestPayload());
        assertInstanceOf(StructureRequestPayload.class, decoded);
        assertNotNull(decoded.type());
    }

    @Test
    void emptySnapshotBatchStillClearsTheClient() {
        final List<List<StructureSnapshot>> batches = StructureSyncProtocol.batchSnapshots(List.of());
        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().isEmpty());
    }

    @Test
    void snapshotRejectsMismatchedCellCount() {
        assertThrows(IllegalArgumentException.class, () -> new StructureSnapshot(
                1L,
                Transform3d.IDENTITY,
                new IntBounds3(0, 0, 0, 2, 2, 2),
                new int[]{1},
                new int[]{1, 0},
                1.0,
                1.0,
                1.0,
                new Vector3d()));
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        final ByteBuf output = Unpooled.buffer();
        try {
            final FriendlyByteBuf writer = new FriendlyByteBuf(output);
            codec.encode(writer, payload);
            final byte[] bytes = new byte[writer.writerIndex()];
            writer.getBytes(0, bytes);

            final ByteBuf input = Unpooled.wrappedBuffer(bytes);
            try {
                return codec.decode(new FriendlyByteBuf(input));
            } finally {
                input.release();
            }
        } finally {
            output.release();
        }
    }
}
