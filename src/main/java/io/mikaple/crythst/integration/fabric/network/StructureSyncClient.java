package io.mikaple.crythst.integration.fabric.network;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.network.structure.StructureBlockEntityPayload;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureRequestPayload;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.network.structure.StructureSyncDebug;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Client-only receivers for structure synchronisation.
 *
 * <p>Isolated from {@link StructureSyncServer} so dedicated servers never load
 * client networking classes.</p>
 */
@Environment(EnvType.CLIENT)
final class StructureSyncClient {
    private static final long RESYNC_THROTTLE_NANOS = 2_000_000_000L;

    private static long lastResyncNanos;

    private StructureSyncClient() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(StructureSnapshotPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (!ClientStructureCache.apply(payload)) {
                        requestFullSyncThrottled();
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(StructurePosePayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (!ClientStructureCache.applyPose(payload)) {
                        requestFullSyncThrottled();
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(StructureRemovePayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (!ClientStructureCache.applyRemove(payload)) {
                        requestFullSyncThrottled();
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(StructureBlockEntityPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (!ClientStructureCache.applyBlockEntities(payload)) {
                        requestFullSyncThrottled();
                    }
                }));

        // Do not clear on JOIN: the server may already have sent the first
        // batch. A fresh connection starts with an empty cache (cleared on
        // disconnect), and full snapshots use a fresh per-player sync id.
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            ClientStructureCache.clear();
            lastResyncNanos = 0L;
        });
        ClientTickEvents.END_CLIENT_TICK.register(StructureSyncClient::tick);
    }

    private static void tick(Minecraft client) {
        if (ClientStructureCache.consumeBlockEntityResyncRequest()) {
            StructureSyncDebug.log("client block-entity base revision mismatch; requesting full resync");
            requestFullSyncThrottled();
        }
        tickDimension(client);
    }

    /**
     * Clears stale structures as soon as the client level changes. The server
     * also sends a fresh full sync, but this closes the window where the client
     * could render the previous dimension's bodies (whose ids may collide).
     */
    private static void tickDimension(Minecraft client) {
        if (client.level == null) {
            if (ClientStructureCache.activeDimension() != null) {
                ClientStructureCache.clear();
            }
            return;
        }
        final Identifier active = ClientStructureCache.activeDimension();
        if (active == null) {
            return;
        }
        final Identifier current = client.level.dimension().identifier();
        if (!active.equals(current)) {
            StructureSyncDebug.log("client dimension changed {} -> {}; clearing structure cache",
                    active, current);
            ClientStructureCache.clear();
            requestFullSyncThrottled();
        }
    }

    /**
     * Sends a full-resync request. Exposed for a future keybind or debug
     * command; the client cache itself is passive.
     */
    public static void requestFullSync() {
        if (Minecraft.getInstance().getConnection() != null) {
            StructureSyncDebug.log("client requests full resync");
            ClientPlayNetworking.send(new StructureRequestPayload());
        }
    }

    /** Rate-limited recovery for stale-session packets. */
    static void requestFullSyncThrottled() {
        final long now = System.nanoTime();
        if (now - lastResyncNanos < RESYNC_THROTTLE_NANOS) {
            return;
        }
        lastResyncNanos = now;
        requestFullSync();
    }

    /** Logs the cache status regardless of the packet-log debug flag. */
    public static void logStatus() {
        Crythst.LOGGER.info(ClientStructureCache.statusText());
    }
}
