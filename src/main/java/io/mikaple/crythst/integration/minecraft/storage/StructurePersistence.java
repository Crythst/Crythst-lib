package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires {@link StructureSavedData} into the Fabric server lifecycle.
 *
 * <p>Add one line to {@code CrythstFabric.onInitialize()}:</p>
 * <pre>{@code
 * StructurePersistence.register();
 * }</pre>
 *
 * <p>Loading happens on {@link ServerLevelEvents#LOAD}; capturing happens on
 * {@link ServerLifecycleEvents#BEFORE_SAVE}, before vanilla asks the level's
 * {@code SavedDataStorage} to write its pending data.</p>
 */
public final class StructurePersistence {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructurePersistence() {
    }

    /** Idempotent registration. */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ServerLevelEvents.LOAD.register((server, level) -> load(level));
        ServerLifecycleEvents.BEFORE_SAVE.register(
                (server, flush, force) -> save(server));
        Crythst.LOGGER.info("Crythst structure persistence registered");
    }

    /**
     * Restores saved structures for one level. Does nothing when the level has
     * no saved structures, so the physics backend is not initialised for empty
     * dimensions.
     */
    public static void load(ServerLevel level) {
        try {
            final StructureSavedData data = level.getDataStorage().get(StructureSavedData.TYPE);
            final ConstraintSavedData constraints =
                    level.getDataStorage().get(ConstraintSavedData.TYPE);
            final boolean hasStructures = data != null && data.structureCount() > 0;
            final boolean hasConstraints = constraints != null && constraints.constraintCount() > 0;
            if (!hasStructures && !hasConstraints) {
                return;
            }
            final LevelPhysics physics = MinecraftPhysics.get(level);
            if (hasStructures) {
                data.restore(level, physics);
            }
            if (hasConstraints) {
                // Constraints are resolved against the stable structure UUIDs,
                // so structures must be live before this call.
                constraints.restore(level, physics, data);
            }
        } catch (Throwable t) {
            Crythst.LOGGER.error("Could not load Crythst structures for {}",
                    level.dimension().identifier(), t);
        }
    }

    /**
     * Captures structures for every level that has a live physics world. Called
     * before vanilla writes saved data, so the records land in the same save.
     */
    public static void save(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            save(level);
        }
    }

    public static void save(ServerLevel level) {
        try {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics == null) {
                return;
            }
            StructureSavedData data = level.getDataStorage().get(StructureSavedData.TYPE);
            if (data == null) {
                if (physics.structures().isEmpty()) {
                    return;
                }
                data = level.getDataStorage().computeIfAbsent(StructureSavedData.TYPE);
            }
            if (!data.restored()) {
                // A level can be created without a LOAD event in tests or when
                // this module is registered late. Restore before capturing so
                // existing records are not mistaken for removed structures.
                data.restore(level, physics);
            }
            // Capture structures first: new bodies get their stable UUIDs here,
            // and constraints reference those UUIDs.
            data.capture(level, physics);

            ConstraintSavedData constraints = level.getDataStorage().get(ConstraintSavedData.TYPE);
            if (constraints == null && physics.world().constraintCount() > 0) {
                constraints = level.getDataStorage().computeIfAbsent(ConstraintSavedData.TYPE);
            }
            if (constraints != null) {
                if (!constraints.restored()) {
                    constraints.restore(level, physics, data);
                }
                constraints.capture(level, physics, data);
            }
        } catch (Throwable t) {
            Crythst.LOGGER.error("Could not save Crythst structures for {}",
                    level.dimension().identifier(), t);
        }
    }
}
