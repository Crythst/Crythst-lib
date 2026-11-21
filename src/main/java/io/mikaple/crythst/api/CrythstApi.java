package io.mikaple.crythst.api;

import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.PhysicsException;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;

/**
 * Public entry point for mods embedding Crythst.
 */
public final class CrythstApi {
    private static volatile PhysicsEngine sharedEngine;

    private CrythstApi() {
    }

    /**
     * Returns the process-wide libbulletjme engine, creating it on first use.
     */
    public static PhysicsEngine engine() {
        PhysicsEngine local = sharedEngine;
        if (local == null) {
            synchronized (CrythstApi.class) {
                local = sharedEngine;
                if (local == null) {
                    local = BulletPhysicsEngine.create();
                    sharedEngine = local;
                }
            }
        }
        return local;
    }

    public static boolean isAvailable() {
        try {
            engine();
            return true;
        } catch (PhysicsException | LinkageError e) {
            return false;
        }
    }

    public static PhysicsWorld createWorld(WorldSettings settings) {
        return engine().createWorld(settings);
    }

    public static PhysicsWorld createWorld() {
        return createWorld(WorldSettings.defaults());
    }

    public static String backendDescription() {
        final PhysicsEngine engine = engine();
        return engine.name() + " " + engine.version();
    }
}
