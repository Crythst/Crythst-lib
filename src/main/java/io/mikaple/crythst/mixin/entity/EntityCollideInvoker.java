package io.mikaple.crythst.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor for vanilla {@code Entity.collide}.
 *
 * <p>The movement mixin replaces the single {@code collide} call inside
 * {@code Entity.move} and must still be able to run the vanilla collision with
 * the structure-clear motion. Calling the private method through this invoker
 * avoids a recursive mixin redirect.</p>
 */
@Mixin(Entity.class)
public interface EntityCollideInvoker {
    @Invoker("collide")
    Vec3 crythst$collide(Vec3 motion);
}
