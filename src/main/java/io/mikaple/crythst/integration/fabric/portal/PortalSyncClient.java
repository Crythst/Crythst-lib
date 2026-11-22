package io.mikaple.crythst.integration.fabric.portal;

import io.mikaple.crythst.client.render.portal.PortalClientCache;
import io.mikaple.crythst.network.portal.PortalRequestPayload;
import io.mikaple.crythst.network.portal.PortalSyncPayload;
import io.mikaple.crythst.network.portal.PortalSyncProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client receiver for the authoritative portal registry snapshot.
 *
 * <p>Payload handlers run on the network thread; the cache mutation is posted
 * to the client thread so the render thread sees an immutable list swap. The
 * cache is cleared on disconnect to avoid rendering a previous server's
 * portals.</p>
 */
@Environment(EnvType.CLIENT)
public final class PortalSyncClient {
    private static boolean registered;

    private PortalSyncClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        PortalSyncProtocol.registerPayloads();
        ClientPlayNetworking.registerGlobalReceiver(PortalSyncPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> PortalClientCache.setFromNetwork(payload)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                ClientPlayNetworking.send(new PortalRequestPayload()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PortalClientCache.clear();
            PortalClientCache.clearNetworkActive();
        });
    }
}
