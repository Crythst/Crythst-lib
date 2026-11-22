package io.mikaple.crythst.mixin.ai;

import io.mikaple.crythst.integration.minecraft.ai.StructurePathSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes ground-navigation target selection see structure surfaces.
 *
 * <p>Vanilla's {@code findSurfacePosition} scans the host chunk only. Above a
 * platform it finds air and drops the target to world terrain. The structure
 * scan uses the already-loaded chunk plus the structure index; when no
 * structure touches the target column the vanilla result is left untouched.</p>
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {
    @Inject(method = "findSurfacePosition", at = @At("HEAD"), cancellable = true)
    private void crythst$structureSurface(LevelChunk chunk, BlockPos pos, int reachRange,
                                          CallbackInfoReturnable<BlockPos> cir) {
        final Level level = chunk.getLevel();
        final BlockPos structureSurface = StructurePathSurface.findSurfacePosition(
                level, pos, reachRange);
        if (structureSurface != null) {
            cir.setReturnValue(structureSurface);
        }
    }
}
