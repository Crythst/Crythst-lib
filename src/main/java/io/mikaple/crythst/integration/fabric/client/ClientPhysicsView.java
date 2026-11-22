package io.mikaple.crythst.integration.fabric.client;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot;
import io.mikaple.crythst.physics.debug.ForceDebugSnapshot;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.TerrainCollisionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Client-thread mirror of the physics data that the server thread exposes for
 * debug rendering.
 *
 * <p>This class is a validation aid, not part of the public API. It only reads
 * the render snapshots published by {@link LevelPhysics}; it never touches the
 * physics world, native bodies or the integrated server from the render thread.
 * Rendering is intentionally limited to singleplayer, where the client and the
 * integrated server share a process and the snapshots are available.</p>
 */
public final class ClientPhysicsView {
    private static volatile List<PhysicsStructure> STRUCTURES = List.of();
    private static volatile PhysicsDebugSnapshot DEBUG;
    private static volatile TerrainCollisionSnapshot TERRAIN;
    private static volatile List<ForceDebugSnapshot> FORCES = List.of();

    private ClientPhysicsView() {
    }

    /**
     * Refreshes the cached snapshots. Called once per client tick, so the
     * renderer never has to query the server thread.
     */
    public static void tick(Minecraft client) {
        if (client.level == null) {
            clear();
            return;
        }
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            // In multiplayer there is no local physics world to visualise.
            clear();
            return;
        }
        final ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            clear();
            return;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics == null) {
            clear();
            return;
        }
        STRUCTURES = physics.renderStructures();
        DEBUG = physics.renderDebugSnapshot();
        TERRAIN = physics.renderTerrainSnapshot();
        FORCES = physics.renderForceSnapshots();
    }

    /**
     * Structures whose render pose was published by the server thread.
     */
    public static List<PhysicsStructure> structures() {
        return STRUCTURES;
    }

    /**
     * Latest debug snapshot, or {@code null} while debug output is disabled.
     */
    public static PhysicsDebugSnapshot debug() {
        return DEBUG;
    }

    /** Latest terrain-collision cache snapshot. */
    public static TerrainCollisionSnapshot terrain() {
        return TERRAIN;
    }

    /** Latest per-structure force/torque snapshot. Never {@code null}. */
    public static List<ForceDebugSnapshot> forceSnapshots() {
        return FORCES;
    }

    public static void clear() {
        STRUCTURES = List.of();
        DEBUG = null;
        TERRAIN = null;
        FORCES = List.of();
    }
}
