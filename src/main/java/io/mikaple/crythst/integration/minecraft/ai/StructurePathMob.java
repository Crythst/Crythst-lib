package io.mikaple.crythst.integration.minecraft.ai;

/**
 * Implemented by the {@code Mob} mixin.
 *
 * <p>Exposes a per-mob structure source so pathfinding helpers do not resolve
 * the physics world on every call. The source is invalidated when the mob
 * changes level. Mods can use this interface to ask which structures currently
 * participate in navigation.</p>
 */
public interface StructurePathMob {
    /** Source for this mob's current level; never {@code null}. */
    StructurePathSource crythst$structurePathSource();
}
