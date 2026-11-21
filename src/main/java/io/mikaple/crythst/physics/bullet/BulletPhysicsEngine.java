package io.mikaple.crythst.physics.bullet;

import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.PhysicsException;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;

/**
 * Process-wide libbulletjme factory.
 */
public final class BulletPhysicsEngine implements PhysicsEngine {
    private final String version;
    private boolean closed;

    private BulletPhysicsEngine(String version) {
        this.version = version;
    }

    /**
     * Loads the native library (once) and returns a new engine instance.
     *
     * @throws PhysicsException when the native library is unavailable
     */
    public static BulletPhysicsEngine create() {
        BulletNatives.load();
        return new BulletPhysicsEngine(com.jme3.bullet.util.NativeLibrary.versionNumber());
    }

    @Override
    public String name() {
        return "libbulletjme";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public PhysicsWorld createWorld(WorldSettings settings) {
        if (closed) {
            throw new IllegalStateException("Physics engine is closed");
        }
        return new BulletPhysicsWorld(this, settings);
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public String toString() {
        return "BulletPhysicsEngine[" + version + "]";
    }
}
