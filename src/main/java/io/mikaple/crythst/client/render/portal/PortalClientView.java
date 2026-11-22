package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalManager;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps {@link PortalClientCache} up to date without touching server state from
 * the render thread.
 *
 * <p>In singleplayer the client tick schedules one small task on the integrated
 * server per tick. That task snapshots {@link PortalManager} and publishes the
 * immutable result; the renderer reads the published list. In multiplayer the
 * cache stays empty until portal networking exists (a later milestone).</p>
 */
public final class PortalClientView {
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static volatile boolean registered;

    private PortalClientView() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(PortalClientView::tick);
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null) {
            PortalClientCache.clear();
            return;
        }
        if (PortalClientCache.networkActive()) {
            // The authoritative S2C snapshot is in use (singleplayer and
            // multiplayer). Do not race it with a direct server query.
            return;
        }
        if (!minecraft.hasSingleplayerServer()) {
            PortalClientCache.clear();
            return;
        }

        final IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            PortalClientCache.clear();
            return;
        }

        // At most one snapshot task in flight. A slow server tick simply means
        // the renderer keeps the previous immutable snapshot for another frame.
        if (!PENDING.compareAndSet(false, true)) {
            return;
        }
        server.execute(() -> {
            try {
                final List<PortalSnapshot> snapshots = PortalManager.snapshots();
                PortalClientCache.set(snapshots);
            } finally {
                PENDING.set(false);
            }
        });
    }
}
