package io.mikaple.crythst.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Read-only diagnostics access to the level-wide block-entity ticker lists.
 * Used by the auxiliary void-level status output.
 */
@Mixin(Level.class)
public interface LevelBlockEntityTickerAccessor {
    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> crythst$blockEntityTickers();

    @Accessor("pendingBlockEntityTickers")
    List<TickingBlockEntity> crythst$pendingBlockEntityTickers();
}
