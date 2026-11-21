package io.mikaple.crythst.api.client;

import net.minecraft.client.Minecraft;

/**
 * Reads the current client partial tick without forcing callers to depend on
 * {@code Minecraft}. Returns {@code 0} outside a running client, which is the
 * correct conservative value for tools and tests.
 */
final class ClientPartialTick {
    private ClientPartialTick() {
    }

    static double current() {
        try {
            final Minecraft client = Minecraft.getInstance();
            if (client == null || client.getDeltaTracker() == null) {
                return 0.0;
            }
            return client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        } catch (RuntimeException | LinkageError ignored) {
            return 0.0;
        }
    }
}
