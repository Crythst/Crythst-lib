package io.mikaple.crythst.mixin.boundary;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Host-side hooks for neighbour and game-event forwarding.
 *
 * <p>Only {@link ServerLevel} methods declared by the target are injected. The
 * auxiliary void level is also a {@code ServerLevel}, but it is not registered
 * in {@code MinecraftPhysics}, so these hooks are inert inside the copy.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBoundaryMixin {
    @Inject(
            method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/Block;"
                    + "Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At("RETURN"))
    private void crythst$forwardNeighborUpdate(BlockPos pos, Block block,
                                               Orientation orientation,
                                               CallbackInfo ci) {
        final ServerLevel level = (ServerLevel) (Object) this;
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics != null) {
            physics.boundary().onHostNeighborUpdate(level, pos, block);
        }
    }

    @Inject(
            method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/Block;"
                    + "Lnet/minecraft/core/Direction;"
                    + "Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At("RETURN"))
    private void crythst$forwardNeighborUpdateExceptFacing(BlockPos pos, Block block,
                                                           net.minecraft.core.Direction facing,
                                                           Orientation orientation,
                                                           CallbackInfo ci) {
        final ServerLevel level = (ServerLevel) (Object) this;
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics != null) {
            physics.boundary().onHostNeighborUpdate(level, pos, block);
        }
    }

    @Inject(
            method = "gameEvent(Lnet/minecraft/core/Holder;"
                    + "Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
            at = @At("HEAD"))
    private void crythst$forwardGameEvent(Holder<GameEvent> event, Vec3 position,
                                          GameEvent.Context context, CallbackInfo ci) {
        final ServerLevel level = (ServerLevel) (Object) this;
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics != null) {
            physics.boundary().onHostGameEvent(level, event, position, context);
        }
    }
}
