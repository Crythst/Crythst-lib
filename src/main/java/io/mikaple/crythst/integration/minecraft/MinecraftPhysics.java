package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.api.CrythstEvents;
import io.mikaple.crythst.api.event.StructureContainerReadyEvent;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.PhysicsException;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one physics world per dimension and ticks them from the server thread.
 */
public final class MinecraftPhysics {
    private static final Map<ResourceKey<Level>, LevelPhysics> LEVELS = new ConcurrentHashMap<>();
    private static CrythstConfig config = new CrythstConfig();
    private static BlockPhysicsMapper blockPhysics = BlockPhysicsMapper.defaults(config);
    private static PhysicsEngine engine;
    private static volatile boolean available = true;

    private MinecraftPhysics() {
    }

    public static void init(CrythstConfig newConfig) {
        config = newConfig;
        blockPhysics = BlockPhysicsMapper.defaults(config);
        available = true;
    }

    public static CrythstConfig config() {
        return config;
    }

    public static void setBlockPhysics(BlockPhysicsMapper mapper) {
        if (mapper != null) {
            blockPhysics = mapper;
            for (LevelPhysics physics : LEVELS.values()) {
                physics.invalidateTerrain();
            }
        }
    }

    public static BlockPhysicsMapper blockPhysics() {
        return blockPhysics;
    }

    public static boolean available() {
        return available;
    }

    public static synchronized PhysicsEngine engine() {
        if (engine == null) {
            try {
                engine = CrythstApi.engine();
            } catch (PhysicsException | LinkageError e) {
                available = false;
                Crythst.LOGGER.error("Physics backend is unavailable; structure physics is disabled", e);
                throw new PhysicsException("libbulletjme is not available on this platform", e);
            }
        }
        return engine;
    }

    public static synchronized LevelPhysics get(ServerLevel level) {
        LevelPhysics physics = LEVELS.get(level.dimension());
        if (physics != null) {
            return physics;
        }
        Crythst.LOGGER.info("Creating physics world for {}", level.dimension().identifier());
        physics = new LevelPhysics(level, engine(), config);
        LEVELS.put(level.dimension(), physics);
        CrythstEvents.fireStructureContainerReady(
                new StructureContainerReadyEvent(level, physics.apiPipeline()));
        return physics;
    }

    public static LevelPhysics getIfPresent(ServerLevel level) {
        return LEVELS.get(level.dimension());
    }

    public static void tick(MinecraftServer server) {
        if (!available || !config.enabled) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            final LevelPhysics physics = LEVELS.get(level.dimension());
            if (physics != null) {
                physics.tick();
            }
        }
    }

    public static void unload(ServerLevel level) {
        final LevelPhysics physics = LEVELS.remove(level.dimension());
        if (physics != null) {
            physics.close();
        }
    }

    public static synchronized void closeAll() {
        for (LevelPhysics physics : LEVELS.values()) {
            physics.close();
        }
        LEVELS.clear();
        PlayerGravity.clearAll();
    }

    public static VoxelVolume volume(ServerLevel level, IntBounds3 bounds) {
        return new BlockVoxelVolume(level, bounds, blockPhysics);
    }
}
