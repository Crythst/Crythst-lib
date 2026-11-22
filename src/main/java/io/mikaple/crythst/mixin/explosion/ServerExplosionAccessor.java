package io.mikaple.crythst.mixin.explosion;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the source/entity-specific explosion damage calculator so the
 * structure blast pass uses exactly the same resistance and block-explode
 * rules as the vanilla explosion that triggered it.
 */
@Mixin(ServerExplosion.class)
public interface ServerExplosionAccessor {
    @Accessor("damageCalculator")
    ExplosionDamageCalculator crythst$damageCalculator();
}
