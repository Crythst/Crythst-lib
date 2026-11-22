package io.mikaple.crythst.network.portal;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers portal sync payloads exactly once per physical side. */
public final class PortalSyncProtocol {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private PortalSyncProtocol() {
    }

    public static void registerPayloads() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        PayloadTypeRegistry.clientboundPlay()
                .register(PortalSyncPayload.TYPE, PortalSyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(PortalRequestPayload.TYPE, PortalRequestPayload.STREAM_CODEC);
    }
}
