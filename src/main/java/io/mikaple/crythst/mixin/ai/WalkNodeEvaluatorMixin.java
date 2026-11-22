package io.mikaple.crythst.mixin.ai;

import io.mikaple.crythst.integration.minecraft.ai.StructurePathDiagnostics;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathRegionAccess;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostics hook for walk-node evaluation.
 *
 * <p>Walk semantics themselves are already structure-aware because every
 * block, fluid, collision and floor query goes through the
 * {@code PathNavigationRegion} bridge. This mixin records whether a path run
 * actually used structures and counts node classifications. It deliberately
 * does not alter node costs, so vanilla mob behaviour stays predictable.</p>
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Unique
    private boolean crythst$structureAware;

    @Inject(method = "prepare", at = @At("RETURN"))
    private void crythst$prepare(PathNavigationRegion level, Mob entity, CallbackInfo ci) {
        crythst$structureAware = level instanceof StructurePathRegionAccess access
                && access.crythst$hasStructurePath();
        StructurePathDiagnostics.pathfindingStarted(crythst$structureAware);
    }

    @Inject(method = "done", at = @At("HEAD"))
    private void crythst$done(CallbackInfo ci) {
        crythst$structureAware = false;
    }

    @Inject(method = "getPathTypeOfMob", at = @At("HEAD"))
    private void crythst$nodeQuery(PathfindingContext context, int x, int y, int z,
                                   Mob mob, CallbackInfo ci) {
        if (crythst$structureAware) {
            StructurePathDiagnostics.nodeTypeQuery();
        }
    }
}
