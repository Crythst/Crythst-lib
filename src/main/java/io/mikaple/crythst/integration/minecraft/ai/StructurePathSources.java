package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Chooses the structure source for a level.
 *
 * <p>The server path is authoritative and client-independent. Client classes
 * are only referenced through a guarded holder, so this class is safe to load
 * on a dedicated server.</p>
 */
public final class StructurePathSources {
    private StructurePathSources() {
    }

    /** Returns a source for this level, or an empty source when unavailable. */
    public static StructurePathSource forLevel(Level level) {
        if (level == null) {
            return StructurePathSource.empty();
        }
        if (level instanceof ServerLevel serverLevel) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
            return physics == null || physics.closed()
                    ? StructurePathSource.empty()
                    : new ServerStructurePathSource(physics);
        }
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            return ClientHolder.of(level);
        }
        return StructurePathSource.empty();
    }

    /** Holds the client-only reference; initialized on first client use only. */
    private static final class ClientHolder {
        private static StructurePathSource of(Level level) {
            return new ClientStructurePathSource(level);
        }
    }
}
