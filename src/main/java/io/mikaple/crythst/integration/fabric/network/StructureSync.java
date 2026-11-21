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
     * <p>Registering this after the physics ticker makes the diff see the pose
     * produced by the current server tick; registering earlier works but sends
     * poses one tick behind.</p>
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
}
