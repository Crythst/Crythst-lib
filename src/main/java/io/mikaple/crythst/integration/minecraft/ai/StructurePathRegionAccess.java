package io.mikaple.crythst.integration.minecraft.ai;

/**
 * Implemented by the {@code PathNavigationRegion} mixin.
 *
 * <p>Other mixins use this interface instead of casting to a concrete mixin
 * class. On a vanilla/obfuscated runtime the interface is still present because
 * the mixin implements it; callers must still guard with
 * {@code instanceof}.</p>
 */
public interface StructurePathRegionAccess {
    /** Whether this region has at least one live structure. */
    boolean crythst$hasStructurePath();

    /** Per-region structure index (never {@code null}). */
    StructurePathIndex crythst$structurePathIndex();
}
