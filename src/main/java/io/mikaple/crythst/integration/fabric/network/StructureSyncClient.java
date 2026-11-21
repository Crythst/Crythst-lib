package io.mikaple.crythst.integration.fabric.network;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureRemovePayload;
import io.mikaple.crythst.network.structure.StructureRequestPayload;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Client-only receivers for structure synchronisation.
 *
 * <p>Isolated from {@link StructureSyncServer} so dedicated servers never load
 * client networking classes.</p>
 */
@Environment(EnvType.CLIENT)
final class StructureSyncClient {
    private StructureSyncClient() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(StructureSnapshotPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientStructureCache.apply(payload)));
        ClientPlayNetworking.registerGlobalReceiver(StructurePosePayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientStructureCache.applyPose(payload)));
        ClientPlayNetworking.registerGlobalReceiver(StructureRemovePayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientStructureCache.applyRemove(payload)));

        // Do not clear on JOIN: the server may already have sent the first
        // batch. A fresh connection starts with an empty cache (cleared on
        // disconnect), and dimension changes use replaceAll snapshots.
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> ClientStructureCache.clear());
    }

    /**
     * Sends a full-resync request. Exposed for a future keybind or debug
     * command; the client cache itself is passive.
     */
    public static void requestFullSync() {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new StructureRequestPayload());
        }
    }
}
