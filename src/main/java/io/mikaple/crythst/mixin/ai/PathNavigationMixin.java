package io.mikaple.crythst.mixin.ai;

import io.mikaple.crythst.integration.minecraft.ai.StructurePathFloor;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathProbe;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathMob;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathProbes;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathSource;
import io.mikaple.crythst.math.Aabb;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Structure-aware follow-up helpers used after a path is computed.
 *
 * <p>Node evaluation sees structures through the region bridge, but
 * {@code getGroundY} and {@code isStableDestination} read the host level
 * directly. Without these hooks a mob can compute a valid path on a platform
 * and then aim its move control at the ground under it.</p>
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow
    @Final
    protected Level level;

    @Shadow
    @Final
    protected Mob mob;

    @Unique
    private @Nullable StructurePathProbe crythst$probe;

    @Inject(method = "getGroundY", at = @At("HEAD"), cancellable = true)
    private void crythst$structureGroundY(Vec3 target,
                                          CallbackInfoReturnable<Double> cir) {
        final BlockPos pos = BlockPos.containing(target.x, target.y, target.z);
        final StructurePathProbe probe = crythst$probeNear(pos);
        if (probe == null) {
            return;
        }
        if (!probe.index().affectsColumn(pos.getX(), pos.getZ())) {
            return;
        }
        final BlockState below = probe.getBlockState(pos.below());
        cir.setReturnValue(below.isAir() ? target.y : StructurePathFloor.floorLevel(probe, pos));
    }

    @Inject(method = "isStableDestination", at = @At("HEAD"), cancellable = true)
    private void crythst$structureStableDestination(BlockPos pos,
                                                    CallbackInfoReturnable<Boolean> cir) {
        final StructurePathProbe probe = crythst$probeNear(pos);
        if (probe == null || !probe.index().affectsColumn(pos.getX(), pos.getZ())) {
            return;
        }
        cir.setReturnValue(probe.getBlockState(pos.below()).isSolidRender());
    }

    /**
     * One probe is reused while the target stays inside its 33x33 region. The
     * index refreshes itself from the source stamp, so a moving structure is
     * still noticed without rebuilding the spatial buckets every tick.
     */
    @Unique
    private @Nullable StructurePathProbe crythst$probeNear(BlockPos pos) {
        final StructurePathProbe cached = crythst$probe;
        if (cached != null) {
            final Aabb region = cached.index().region();
            if (region != null && region.contains(new Vector3d(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))) {
                return cached;
            }
        }
        final StructurePathSource source = mob instanceof StructurePathMob access
                ? access.crythst$structurePathSource()
                : null;
        crythst$probe = StructurePathProbes.near(source, level, pos, 16);
        return crythst$probe;
    }
}
