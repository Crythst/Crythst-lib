package io.mikaple.crythst.mixin;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Invalidates cached terrain collision for the section containing an edited
 * block. The next terrain refresh rebuilds that section from the live world.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkTerrainMixin {
    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void crythst$invalidateTerrainSection(BlockPos pos, BlockState state, int flags,
                                                  CallbackInfoReturnable<BlockState> cir) {
        final BlockState previous = cir.getReturnValue();
        if (previous == null || previous == state) {
            // Null means the edit was rejected (for example an unloaded
            // position); an identical state does not change collision.
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!crythst$affectsCollision(serverLevel, pos, previous, state)) {
            return;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
        if (physics != null) {
            physics.invalidateTerrainAt(pos);
        }
    }

    /**
     * Fluid levels, redstone power and other state-only changes cannot alter
     * a cell's collision emptiness. Skipping them avoids rebuilding a section
     * on every water tick. When either state has collision (or the collision
     * shape changes between two non-empty states) the section is invalidated.
     */
    private static boolean crythst$affectsCollision(Level level, BlockPos pos,
                                                    BlockState previous, BlockState next) {
        final boolean previousSolid = !previous.isAir()
                && !previous.getCollisionShape(level, pos).isEmpty();
        final boolean nextSolid = !next.isAir()
                && !next.getCollisionShape(level, pos).isEmpty();
        return previousSolid || nextSolid;
    }
}
