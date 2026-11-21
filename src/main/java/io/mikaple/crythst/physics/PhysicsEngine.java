package io.mikaple.crythst.physics;

import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;

/**
 * Factory for physics worlds. An engine owns process-wide native resources and
 * should be reused; worlds are independent simulation universes.
 */
public interface PhysicsEngine extends AutoCloseable {
    /**
     * Human-readable backend name, for logs and commands.
     */
    String name();

    /**
     * Backend version string.
     */
    String version();

    PhysicsWorld createWorld(WorldSettings settings);

    boolean closed();

    @Override
    void close();
}
