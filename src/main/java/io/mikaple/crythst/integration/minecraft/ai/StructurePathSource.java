package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.math.Aabb;

import java.util.List;

/**
 * Provides the physics structures that can participate in a pathfinding
 * query.
 *
 * <p>A source is resolved once per {@code PathNavigationRegion}. The source
 * owns the "which structures exist right now" question; a
 * {@link StructurePathIndex} owns the per-region spatial index and the
 * world-coordinate sampling. Implementations must be read-only and must never
 * load a chunk.</p>
 *
 * <p>{@link #stamp()} changes whenever the structure set, a structure's block
 * content, or a structure pose changes. A navigation index compares this stamp
 * periodically and rebuilds rather than serving stale geometry.</p>
 */
public interface StructurePathSource {
    /**
     * Structures whose world bounds intersect {@code query}.
     *
     * <p>The returned list may be truncated to a hard bound; callers must not
     * assume it is complete. The query only describes the region being
     * indexed, so a path region never has to walk every structure in the
     * dimension on each block lookup.</p>
     */
    List<StructurePathVolume> snapshot(Aabb query);

    /** Monotonic-ish revision used for cache invalidation. */
    long stamp();

    /** Short label for diagnostics ({@code server}, {@code client}). */
    String side();

    /** Source with no structures. */
    static StructurePathSource empty() {
        return EmptySource.INSTANCE;
    }

    enum EmptySource implements StructurePathSource {
        INSTANCE;

        @Override
        public List<StructurePathVolume> snapshot(Aabb query) {
            return List.of();
        }

        @Override
        public long stamp() {
            return 0L;
        }

        @Override
        public String side() {
            return "none";
        }
    }
}
