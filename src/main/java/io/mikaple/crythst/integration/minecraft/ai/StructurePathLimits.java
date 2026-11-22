package io.mikaple.crythst.integration.minecraft.ai;

/**
 * Hard bounds for structure-aware pathfinding.
 *
 * <p>Pathfinding runs on the server tick and must not let a pathological
 * structure (or a structure-dense area) turn node expansion into an unbounded
 * loop. Every bound is a fallback threshold: once exceeded the path index
 * stops returning structure blocks and vanilla pathfinding continues with the
 * real world.</p>
 */
public final class StructurePathLimits {
    /** Maximum structures indexed for one navigation region. */
    public static final int MAX_STRUCTURES = 64;

    /** A structure spanning more columns than this is indexed globally. */
    public static final int MAX_CHUNK_BUCKETS_PER_STRUCTURE = 4096;

    /** Maximum candidate structures tested for one world block. */
    public static final int MAX_CANDIDATES_PER_QUERY = 32;

    /** Maximum structure block lookups for one navigation region. */
    public static final int MAX_QUERIES_PER_REGION =
            intProperty("crythst.pathfinding.maxStructureQueries", 262_144);

    /** How often a region index checks whether the source stamp changed. */
    public static final int VALIDATION_INTERVAL = 128;

    /** Maximum automatic index rebuilds after a pose/edit change. */
    public static final int MAX_REBUILDS = 4;

    /** Maximum vertical blocks scanned when locating a surface. */
    public static final int MAX_SURFACE_SCAN = 128;

    private StructurePathLimits() {
    }

    private static int intProperty(String key, int fallback) {
        try {
            final String raw = System.getProperty(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            final int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
