package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.math.Aabb;

import java.util.List;

/**
 * Read-only view of the structures that an entity can collide with in one
 * dimension.
 *
 * <p>The same interface is implemented by the server physics world, the
 * singleplayer client view and the multiplayer network cache. The movement
 * solver only uses {@link #candidates(Aabb)}; it never writes to a structure.</p>
 */
public interface StructureCollisionSource {
    /**
     * Structures whose broad-phase bounds intersect {@code query}. The query
     * is normally the entity AABB expanded by the whole motion, so a swept
     * substep can only hit one of the returned structures.
     */
    List<StructureCollisionBox> candidates(Aabb query);

    /**
     * Largest positional correction the solver may apply per tick. This keeps
     * a stale client snapshot from flinging an entity across the world while
     * still allowing a deeply embedded entity to be pushed out.
     */
    double maxCorrection();

    /** Short label for diagnostics ({@code server}, {@code client}). */
    String side();
}
