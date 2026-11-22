package io.mikaple.crythst.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read-only access to the live block-entity map. The field is declared on
 * {@link ChunkAccess}, not on {@code LevelChunk}, so the accessor must target
 * the superclass to be found by the mixin applicator.
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessBlockEntityAccessor {
    @Accessor("blockEntities")
    Map<BlockPos, BlockEntity> crythst$blockEntities();
}
