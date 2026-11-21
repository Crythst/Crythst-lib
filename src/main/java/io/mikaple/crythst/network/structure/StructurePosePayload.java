package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.math.Transform3d;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-to-client pose update for structures that already exist on the client.
 *
 * <p>Packets are batched by the server's end-of-tick diff. A client that has not
 * received the matching snapshot ignores updates for unknown body ids.</p>
 */
public record StructurePosePayload(List<PoseUpdate> updates) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StructurePosePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_pose"));

    public static final StreamCodec<FriendlyByteBuf, StructurePosePayload> STREAM_CODEC =
            StreamCodec.of(StructurePosePayload::write, StructurePosePayload::read);

    public StructurePosePayload {
        updates = List.copyOf(updates);
        if (updates.size() > StructureSyncLimits.MAX_POSE_UPDATES) {
            throw new IllegalArgumentException("Too many pose updates: " + updates.size());
        }
    }

    public record PoseUpdate(long bodyId, Transform3d pose) {
        public PoseUpdate {
            if (bodyId < 0L) {
                throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
            }
            pose = Objects.requireNonNull(pose, "pose");
        }
    }

    private static void write(FriendlyByteBuf buf, StructurePosePayload payload) {
        buf.writeVarInt(payload.updates().size());
        for (PoseUpdate update : payload.updates()) {
            buf.writeVarLong(update.bodyId());
            StructureSnapshotCodec.writePose(buf, update.pose());
        }
    }

    private static StructurePosePayload read(FriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        if (count < 0 || count > StructureSyncLimits.MAX_POSE_UPDATES) {
            throw new DecoderException("Invalid pose update count " + count);
        }
        final List<PoseUpdate> updates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final long bodyId = buf.readVarLong();
            if (bodyId < 0L) {
                throw new DecoderException("Invalid body id " + bodyId);
            }
            updates.add(new PoseUpdate(bodyId, StructureSnapshotCodec.readPose(buf)));
        }
        try {
            return new StructurePosePayload(updates);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid pose payload", e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
