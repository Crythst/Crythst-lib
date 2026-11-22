package io.mikaple.crythst.network.portal;

import io.mikaple.crythst.Crythst;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Client prediction notice for a same-dimension player crossing.
 *
 * <p>All vectors are pre-transform values captured from the client tick. The
 * server re-validates the swept crossing against the authoritative portal
 * definition and applies the rigid transform itself; it never accepts a target
 * position from the client.</p>
 */
public record PortalTraversalPayload(
        UUID portalId,
        Vector3d previousCentre,
        Vector3d currentCentre,
        Vector3d eye,
        Vector3d velocity,
        float yaw,
        float pitch
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PortalTraversalPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_traversal"));

    public static final StreamCodec<FriendlyByteBuf, PortalTraversalPayload> STREAM_CODEC =
            StreamCodec.of(PortalTraversalPayload::write, PortalTraversalPayload::read);

    private static void write(FriendlyByteBuf buf, PortalTraversalPayload payload) {
        buf.writeUUID(payload.portalId);
        writeVector(buf, payload.previousCentre);
        writeVector(buf, payload.currentCentre);
        writeVector(buf, payload.eye);
        writeVector(buf, payload.velocity);
        buf.writeFloat(payload.yaw);
        buf.writeFloat(payload.pitch);
    }

    private static PortalTraversalPayload read(FriendlyByteBuf buf) {
        return new PortalTraversalPayload(
                buf.readUUID(),
                readVector(buf),
                readVector(buf),
                readVector(buf),
                readVector(buf),
                buf.readFloat(),
                buf.readFloat());
    }

    private static void writeVector(FriendlyByteBuf buf, Vector3d vector) {
        buf.writeDouble(vector.x);
        buf.writeDouble(vector.y);
        buf.writeDouble(vector.z);
    }

    private static Vector3d readVector(FriendlyByteBuf buf) {
        return new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
