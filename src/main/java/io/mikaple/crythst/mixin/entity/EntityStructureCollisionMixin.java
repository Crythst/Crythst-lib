package io.mikaple.crythst.mixin.entity;

import io.mikaple.crythst.integration.minecraft.collision.EntityStructureCollision;
import net.minecraft.world.entity.Entity;
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
    private Vec3 crythst$resolveStructureMotion(Entity instance, Vec3 motion) {
        final Entity self = (Entity) (Object) this;
        return EntityStructureCollision.resolveMovement(self, motion,
                allowed -> ((EntityCollideInvoker) self).crythst$collide(allowed));
    }
}
