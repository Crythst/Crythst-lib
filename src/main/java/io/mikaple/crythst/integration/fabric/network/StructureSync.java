package io.mikaple.crythst.integration.fabric.network;

import io.mikaple.crythst.network.structure.StructureSyncProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Entry point for structure synchronisation.
 *
 * <p>Call {@link #registerCommon()} from the common mod initializer and
 * {@link #registerClient()} from the client initializer.</p>
 */
public final class StructureSync {
    private StructureSync() {
    }

    /**
     * Registers payload codecs, the server request receiver, and the
     * end-of-tick broadcaster. Safe to call once per process.
     *
     * <p>The broadcaster registers its tick listener on {@code SERVER_STARTED},
     * so it always runs after {@code PhysicsTicker}'s end-of-tick listener
     * regardless of mod-initializer order.</p>
     */
    public static void registerCommon() {
        StructureSyncProtocol.registerPayloads();
        StructureSyncServer.register();
    }

    /**
     * Registers client receivers and the disconnect cache reset. Must only be
     * called from a physical client.
     */
    public static void registerClient() {
        StructureSyncProtocol.registerPayloads();
        StructureSyncClient.register();
    }

    /**
     * Requests a fresh full snapshot from the server. Client-side only; useful
     * for a debug keybind after a client-side reload.
     */
    @Environment(EnvType.CLIENT)
    public static void requestFullSync() {
        StructureSyncClient.requestFullSync();
    }

    /** Server-side sync counters, for a debug command/overlay. */
    public static String serverStatusText() {
        return StructureSyncServer.statusText();
    }

    /** Client-side cache/session counters, for a debug command/overlay. */
    @Environment(EnvType.CLIENT)
    public static String clientStatusText() {
        return io.mikaple.crythst.client.network.ClientStructureCache.statusText();
    }

    /** Logs the client cache state. Useful after a resync or client reload. */
    @Environment(EnvType.CLIENT)
    public static void logClientStatus() {
        StructureSyncClient.logStatus();
    }
}
