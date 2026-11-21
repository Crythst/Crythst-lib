package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Chooses the read-only structure source for an entity's dimension.
 *
 * <p>Server levels use the authoritative physics world. Client levels use the
 * client holder, which is only class-loaded after an explicit environment
 * check, so a dedicated server never touches client-only classes.</p>
 */
public final class StructureCollisionSources {
    private StructureCollisionSources() {
    }

    /** Returns the source for this entity, or {@code null} when none exists. */
    public static StructureCollisionSource forEntity(Entity entity) {
        final Level level = entity.level();
        if (level instanceof ServerLevel serverLevel) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
            return physics == null
                    ? ServerStructureCollisionSource.of(null)
                    : ServerStructureCollisionSource.of(physics);
        }
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            return ClientHolder.of(level);
        }
        return null;
    }

    /**
     * Holds the client-only reference. The class is initialized on first use,
     * which only happens on a physical client.
     */
    private static final class ClientHolder {
        private static StructureCollisionSource of(Level level) {
            return ClientStructureCollisionSource.of(level);
        }
    }
}
