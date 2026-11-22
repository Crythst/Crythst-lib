package io.mikaple.crythst.integration.fabric.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalManager;
import io.mikaple.crythst.integration.portal.PortalTeleporter;
import io.mikaple.crythst.network.portal.PortalRequestPayload;
import io.mikaple.crythst.network.portal.PortalSyncPayload;
import io.mikaple.crythst.network.portal.PortalSyncProtocol;
import io.mikaple.crythst.network.portal.PortalTraversalPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-authoritative portal registry broadcaster.
 *
 * <p>Portal definitions live only on the server. Clients receive a full
 * immutable snapshot on join, after an explicit request, and whenever
 * {@link PortalManager#revision()} changes. The renderer never reads a server
 * object; it reads the client cache filled by this payload.</p>
 */
public final class PortalSyncServer {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile long lastBroadcastRevision = -1L;

    private PortalSyncServer() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        PortalSyncProtocol.registerPayloads();

        ServerPlayNetworking.registerGlobalReceiver(PortalRequestPayload.TYPE,
                (payload, context) -> sendFull(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(PortalTraversalPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> PortalTeleporter.acceptClientTraversal(context.player(), payload)));
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> sendFull(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(PortalSyncServer::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> lastBroadcastRevision = -1L);
    }

    private static void tick(MinecraftServer server) {
        final long revision = PortalManager.revision();
        if (revision == lastBroadcastRevision) {
            return;
        }
        lastBroadcastRevision = revision;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendFull(player);
        }
    }

    /** Sends the current registry to one player even if the revision is old. */
    public static void sendFull(ServerPlayer player) {
        if (player == null) {
            return;
        }
        final long revision = PortalManager.revision();
        try {
            ServerPlayNetworking.send(player,
                    new PortalSyncPayload(revision, PortalManager.snapshots()));
        } catch (Throwable error) {
            Crythst.LOGGER.warn("Could not send portal snapshot to {}", player.getName().getString(),
                    error);
        }
    }
}
