package io.mikaple.crythst.mixin.boundary;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Host-world redstone reads for blocks that live in a physics structure.
 *
 * <p>{@link SignalGetter}'s {@code getSignal} and {@code getDirectSignal}
 * defaults are the narrowest correct hook: only signal queries are redirected,
 * while {@code getBlockState} keeps returning real host-world air so vanilla
 * collision, AI and fluid queries do not suddenly see structure voxels. The
 * structure state is read by the boundary bridge through the auxiliary
 * level.</p>
 *
 * <p>This is an interface mixin on purpose. The methods being injected are
 * default methods declared by {@link SignalGetter}; injecting into them keeps
 * the remapper's refmap intact in production, unlike adding concrete overrides
 * to {@code Level}.</p>
 */
@Mixin(SignalGetter.class)
public interface SignalGetterBoundaryMixin {
    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    default void crythst$bridgeGetSignal(BlockPos pos, Direction direction,
                                         CallbackInfoReturnable<Integer> cir) {
        if (!(this instanceof ServerLevel serverLevel)) {
            return;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
        if (physics == null) {
            return;
        }
        final int bridged = physics.boundary().hostSignal(serverLevel, pos, direction);
        if (bridged >= 0) {
            cir.setReturnValue(bridged);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    default void crythst$bridgeGetDirectSignal(BlockPos pos, Direction direction,
                                               CallbackInfoReturnable<Integer> cir) {
        if (!(this instanceof ServerLevel serverLevel)) {
            return;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
        if (physics == null) {
            return;
        }
        final int bridged = physics.boundary().hostDirectSignal(serverLevel, pos, direction);
        if (bridged >= 0) {
            cir.setReturnValue(bridged);
        }
    }
}
