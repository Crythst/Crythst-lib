package io.mikaple.crythst.integration.minecraft.mass;

import io.mikaple.crythst.Crythst;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers the block-mass registry as a server data-pack reload listener.
 *
 * <p>Call {@link #register()} once from the common Fabric initialiser. Only
 * {@link PackType#SERVER_DATA} is used, so a single-player client never runs a
 * second client-side resource reload for physics data.</p>
 */
public final class BlockMassReload {
    /** Reload-listener id (not a resource path; the JSON path is in the registry). */
    public static final Identifier LISTENER_ID =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "block_physics");

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private BlockMassReload() {
    }

    /**
     * Installs the listener. Safe to call more than once; only the first call
     * registers it.
     */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new Listener());
            Crythst.LOGGER.info("Block-mass data reload listener registered for {}", BlockPhysicsRegistry.DATA_PATH);
        }
    }

    public static boolean registered() {
        return REGISTERED.get();
    }

    /**
     * Reloads immediately from a resource manager. Exposed for tests and for
     * integrations that already own the server's reload lifecycle.
     */
    public static boolean reloadNow(ResourceManager manager) {
        return BlockPhysicsRegistry.shared().load(manager);
    }

    private static final class Listener implements SimpleSynchronousResourceReloadListener {
        @Override
        public Identifier getFabricId() {
            return LISTENER_ID;
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            reloadNow(manager);
        }
    }
}
