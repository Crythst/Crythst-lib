package io.mikaple.crythst.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

/**
 * Exposes the server storage/executor needed to construct an auxiliary void
 * {@code ServerLevel} for block ticking.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("executor")
    Executor crythst$executor();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess crythst$storageSource();
}
