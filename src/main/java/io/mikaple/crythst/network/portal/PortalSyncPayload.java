package io.mikaple.crythst.network.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalPlane;
import io.mikaple.crythst.integration.portal.PortalShape;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector2d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full server-to-client portal registry snapshot.
 *
 * <p>A portal list is small (tens of entries, not thousands), so one bounded
 * packet is simpler and less error-prone than delta batching. Every packet
 * carries a monotonically increasing revision; the client applies every
 * snapshot because it is authoritative, but uses the revision for diagnostics.
 * The renderer still consumes only immutable {@link PortalSnapshot}s.</p>
 */
public record PortalSyncPayload(long revision, List<PortalSnapshot> portals)
        implements CustomPacketPayload {
    public static final int MAX_PORTALS = 256;
    public static final int MAX_OUTLINE_POINTS = 64;
    public static final int MAX_NAME_LENGTH = 48;

    public static final CustomPacketPayload.Type<PortalSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_sync"));

    public static final StreamCodec<FriendlyByteBuf, PortalSyncPayload> STREAM_CODEC =
            StreamCodec.of(PortalSyncPayload::write, PortalSyncPayload::read);

    public PortalSyncPayload {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        portals = List.copyOf(portals);
        if (portals.size() > MAX_PORTALS) {
            throw new IllegalArgumentException("Too many portals: " + portals.size());
        }
    }

    private static void write(FriendlyByteBuf buf, PortalSyncPayload payload) {
        buf.writeVarLong(payload.revision());
        buf.writeVarInt(payload.portals().size());
        for (PortalSnapshot portal : payload.portals()) {
            buf.writeUUID(portal.id());
            buf.writeUtf(portal.name(), MAX_NAME_LENGTH);
            buf.writeIdentifier(portal.dimension().identifier());
            writePlane(buf, portal.plane());
            writeOutline(buf, portal.shape());
            buf.writeBoolean(portal.linkedPortalId() != null);
            if (portal.linkedPortalId() != null) {
                buf.writeUUID(portal.linkedPortalId());
                buf.writeBoolean(portal.destinationPlane() != null);
                if (portal.destinationPlane() != null) {
                    writePlane(buf, portal.destinationPlane());
                    writeOutline(buf, portal.destinationShape());
                    final Identifier destinationDimension =
                            portal.destinationDimension().identifier();
                    buf.writeIdentifier(destinationDimension);
                }
            }
        }
    }

    private static PortalSyncPayload read(FriendlyByteBuf buf) {
        final long revision = buf.readVarLong();
        if (revision < 0L) {
            throw new DecoderException("Invalid portal revision " + revision);
        }
        final int count = buf.readVarInt();
        if (count < 0 || count > MAX_PORTALS) {
            throw new DecoderException("Invalid portal count " + count);
        }
        final List<PortalSnapshot> portals = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                final UUID id = buf.readUUID();
                final String name = buf.readUtf(MAX_NAME_LENGTH);
                final ResourceKey<Level> dimension = readDimension(buf);
                final PortalPlane plane = readPlane(buf);
                final List<Vector2d> outline = readOutline(buf);
                final PortalShape shape = outline.isEmpty()
                        ? PortalShape.rectangle(plane) : PortalShape.polygon(plane, outline);
                final boolean linked = buf.readBoolean();
                UUID linkedId = null;
                PortalPlane destinationPlane = null;
                PortalShape destinationShape = null;
                ResourceKey<Level> destinationDimension = null;
                if (linked) {
                    linkedId = buf.readUUID();
                    if (buf.readBoolean()) {
                        destinationPlane = readPlane(buf);
                        final List<Vector2d> destinationOutline = readOutline(buf);
                        destinationShape = destinationOutline.isEmpty()
                                ? PortalShape.rectangle(destinationPlane)
                                : PortalShape.polygon(destinationPlane, destinationOutline);
                        destinationDimension = readDimension(buf);
                    }
                }
                portals.add(new PortalSnapshot(id, name, dimension, plane, shape, linkedId,
                        destinationPlane, destinationShape, destinationDimension));
            }
        } catch (IllegalArgumentException error) {
            throw new DecoderException("Invalid portal snapshot", error);
        }
        return new PortalSyncPayload(revision, portals);
    }

    private static void writePlane(FriendlyByteBuf buf, PortalPlane plane) {
        final Vector3d origin = plane.origin();
        final Vector3d normal = plane.normal();
        final Vector3d right = plane.right();
        buf.writeDouble(origin.x);
        buf.writeDouble(origin.y);
        buf.writeDouble(origin.z);
        buf.writeDouble(normal.x);
        buf.writeDouble(normal.y);
        buf.writeDouble(normal.z);
        buf.writeDouble(right.x);
        buf.writeDouble(right.y);
        buf.writeDouble(right.z);
        buf.writeDouble(plane.width());
        buf.writeDouble(plane.height());
    }

    private static PortalPlane readPlane(FriendlyByteBuf buf) {
        return new PortalPlane(
                new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readDouble(), buf.readDouble());
    }

    private static void writeOutline(FriendlyByteBuf buf, PortalShape shape) {
        if (shape == null || shape.rectangular()) {
            buf.writeVarInt(0);
            return;
        }
        final List<Vector2d> outline = shape.outline();
        buf.writeVarInt(outline.size());
        for (Vector2d point : outline) {
            buf.writeDouble(point.x);
            buf.writeDouble(point.y);
        }
    }

    private static List<Vector2d> readOutline(FriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        if (count < 0 || count > MAX_OUTLINE_POINTS) {
            throw new DecoderException("Invalid outline point count " + count);
        }
        if (count == 0) {
            return List.of();
        }
        if (count < 3) {
            throw new DecoderException("Outline needs at least 3 points");
        }
        final List<Vector2d> outline = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            outline.add(new Vector2d(buf.readDouble(), buf.readDouble()));
        }
        return List.copyOf(outline);
    }

    private static ResourceKey<Level> readDimension(FriendlyByteBuf buf) {
        return ResourceKey.create(Registries.DIMENSION, buf.readIdentifier());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
