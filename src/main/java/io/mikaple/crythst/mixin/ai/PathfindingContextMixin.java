package io.mikaple.crythst.mixin.ai;

import io.mikaple.crythst.integration.minecraft.ai.StructurePathNodeTypes;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bypasses the server-wide {@code PathTypeCache} for structure-aware regions.
 *
 * <p>In 26.3 {@code PathfindingContext} caches node classifications in
 * {@code ServerLevel#getPathTypeCache()}, keyed only by block position. That
 * cache is populated by earlier pathfinding queries that saw host air. Even
 * after a structure enters the area, the cached {@code OPEN} would win and the
 * new region-aware {@code getBlockState} would never be consulted. Structure
 * regions therefore classify nodes directly; {@code WalkNodeEvaluator}'s own
 * per-path cache still amortizes repeated queries.</p>
 */
@Mixin(PathfindingContext.class)
public abstract class PathfindingContextMixin {
    @Shadow
    @Final
    private CollisionGetter level;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutablePos;

    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private void crythst$structureAwarePathType(int x, int y, int z,
                                                CallbackInfoReturnable<PathType> cir) {
        if (level instanceof StructurePathRegionAccess access
                && access.crythst$hasStructurePath()) {
            cir.setReturnValue(StructurePathNodeTypes.pathType(
                    level, mutablePos.set(x, y, z)));
        }
    }
}
