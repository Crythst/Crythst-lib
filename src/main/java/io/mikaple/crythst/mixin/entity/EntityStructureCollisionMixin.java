package io.mikaple.crythst.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import io.mikaple.crythst.integration.minecraft.collision.EntityStructureCollision;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Runs physics-structure collision inside vanilla entity movement.
 *
 * <p>Vanilla {@code Entity.move} calls {@code collide(motion)} exactly once
 * and then moves by the returned vector, computing step handling, fall damage
 * and the {@code onGround}/{@code verticalCollision} flags from that result.
 * Redirecting that call lets the structure collision participate in the same
 * movement instead of correcting the entity after the tick. Sliding, tangential
 * motion, ramps and the flags therefore keep their vanilla semantics.</p>
 */
@Mixin(Entity.class)
public abstract class EntityStructureCollisionMixin {
    @Redirect(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 crythst$resolveStructureMotion(Entity entity, Vec3 motion,
                                               @Share("worldClipX") LocalBooleanRef worldClipX,
                                               @Share("worldClipZ") LocalBooleanRef worldClipZ) {
        return EntityStructureCollision.resolveMovement(entity, motion, structureMotion -> {
            final Vec3 worldMotion = ((EntityCollideInvoker) entity).crythst$collide(structureMotion);
            worldClipX.set(!Mth.equal(structureMotion.x, worldMotion.x));
            worldClipZ.set(!Mth.equal(structureMotion.z, worldMotion.z));
            return worldMotion;
        });
    }

    @WrapOperation(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;restituteMovementAfterCollisions(Lnet/minecraft/world/level/block/state/BlockState;ZZLnet/minecraft/world/phys/Vec3;)V"
            )
    )
    private void crythst$applyWorldRestitution(Entity entity, BlockState state,
                                              boolean clippedX, boolean clippedZ, Vec3 motion,
                                              Operation<Void> original,
                                              @Share("worldClipX") LocalBooleanRef worldClipX,
                                              @Share("worldClipZ") LocalBooleanRef worldClipZ) {
        // Structure normals already filtered velocity. Only world blocks can
        // apply axis restitution to the remaining horizontal components.
        original.call(entity, state, worldClipX.get(), worldClipZ.get(), motion);
    }
}
