package io.mikaple.crythst.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read-only diagnostics access to the block-entity ticker bookkeeping of a
 * loaded chunk.
 *
 * <p>The auxiliary void level cannot use {@code Level#tickBlockEntities} alone
 * as a status signal: a ticker can be registered but still skip its tick when
 * the chunk is not fully promoted or its entity section is not loaded. These
 * accessors let {@code EmptyPhysicsLevel} report the real registration and load
 * state without changing vanilla behaviour.</p>
 */
@Mixin(LevelChunk.class)
public interface LevelChunkTickAccessor {
    @Accessor("loaded")
    boolean crythst$isLoaded();

    @Accessor("tickersInLevel")
    Map<BlockPos, ?> crythst$blockEntityTickers();
}
