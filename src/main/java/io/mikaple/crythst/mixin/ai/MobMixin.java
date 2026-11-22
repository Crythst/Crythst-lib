package io.mikaple.crythst.mixin.ai;

import io.mikaple.crythst.integration.minecraft.ai.StructurePathMob;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathSource;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathSources;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Per-mob structure source cache.
 *
 * <p>Mobs on moving structures ask for a source once per level, not once per
 * path node. This is also the extension point for mods that want to log or
 * inspect structure-aware navigation.</p>
 */
@Mixin(Mob.class)
public abstract class MobMixin implements StructurePathMob {
    @Unique
    private @Nullable StructurePathSource crythst$structureSource;

    @Unique
    private @Nullable Level crythst$structureSourceLevel;

    @Override
    public StructurePathSource crythst$structurePathSource() {
        final Level level = ((Mob) (Object) this).level();
        if (crythst$structureSource == null || crythst$structureSourceLevel != level) {
            crythst$structureSource = StructurePathSources.forLevel(level);
            crythst$structureSourceLevel = level;
        }
        return crythst$structureSource;
    }
}
