package io.mikaple.crythst.mixin.boundary;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to vanilla's private hopper cooldown. The boundary bridge uses the
 * same eight-tick cooldown as a normal hopper so a host hopper next to a
 * structure container cannot move items faster than a vanilla hopper.
 */
@Mixin(HopperBlockEntity.class)
public interface HopperBoundaryAccessor {
    @Invoker("setCooldown")
    void crythst$setCooldown(int cooldown);

    @Invoker("isOnCooldown")
    boolean crythst$isOnCooldown();
}
