package io.mikaple.crythst.integration.minecraft.ai;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters for structure-aware pathfinding.
 *
 * <p>No logging happens on the hot path. A command or debug screen can call
 * {@link #snapshot()} / {@link #describe()} to show whether structure blocks
 * are actually participating in node queries, whether the per-region budget
 * was hit, and how many cache rebuilds occurred.</p>
 */
public final class StructurePathDiagnostics {
    private static final AtomicLong REGIONS = new AtomicLong();
    private static final AtomicLong REGIONS_WITH_STRUCTURES = new AtomicLong();
    private static final AtomicLong INDEX_BUILDS = new AtomicLong();
    private static final AtomicLong INDEX_REBUILDS = new AtomicLong();
    private static final AtomicLong STALE_REBUILDS = new AtomicLong();
    private static final AtomicLong BLOCK_QUERIES = new AtomicLong();
    private static final AtomicLong FLUID_QUERIES = new AtomicLong();
    private static final AtomicLong STRUCTURE_BLOCK_HITS = new AtomicLong();
    private static final AtomicLong STRUCTURE_FLUID_HITS = new AtomicLong();
    private static final AtomicLong CANDIDATE_TESTS = new AtomicLong();
    private static final AtomicLong BUDGET_EXHAUSTED = new AtomicLong();
    private static final AtomicLong SKIPPED_STRUCTURES = new AtomicLong();
    private static final AtomicLong PATHFINDING_RUNS = new AtomicLong();
    private static final AtomicLong STRUCTURE_PATHFINDING_RUNS = new AtomicLong();
    private static final AtomicLong NODE_TYPE_QUERIES = new AtomicLong();
    private static final AtomicLong SURFACE_QUERIES = new AtomicLong();
    private static final AtomicLong SURFACE_HITS = new AtomicLong();

    private StructurePathDiagnostics() {
    }

    public record Snapshot(
            long regions,
            long regionsWithStructures,
            long indexBuilds,
            long indexRebuilds,
            long staleRebuilds,
            long blockQueries,
            long fluidQueries,
            long structureBlockHits,
            long structureFluidHits,
            long candidateTests,
            long budgetExhausted,
            long skippedStructures,
            long pathfindingRuns,
            long structurePathfindingRuns,
            long nodeTypeQueries,
            long surfaceQueries,
            long surfaceHits
    ) {
        public String describe() {
            return "path[regions=" + regions
                    + " withStructures=" + regionsWithStructures
                    + " builds=" + indexBuilds
                    + " rebuilds=" + indexRebuilds
                    + " stale=" + staleRebuilds
                    + " blockQ=" + blockQueries
                    + " fluidQ=" + fluidQueries
                    + " blockHits=" + structureBlockHits
                    + " fluidHits=" + structureFluidHits
                    + " candidates=" + candidateTests
                    + " budgetHit=" + budgetExhausted
                    + " skipped=" + skippedStructures
                    + " runs=" + pathfindingRuns
                    + " structureRuns=" + structurePathfindingRuns
                    + " nodeQ=" + nodeTypeQueries
                    + " surfaceQ=" + surfaceQueries
                    + " surfaceHits=" + surfaceHits + "]";
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                REGIONS.get(), REGIONS_WITH_STRUCTURES.get(), INDEX_BUILDS.get(),
                INDEX_REBUILDS.get(), STALE_REBUILDS.get(), BLOCK_QUERIES.get(),
                FLUID_QUERIES.get(), STRUCTURE_BLOCK_HITS.get(), STRUCTURE_FLUID_HITS.get(),
                CANDIDATE_TESTS.get(), BUDGET_EXHAUSTED.get(), SKIPPED_STRUCTURES.get(),
                PATHFINDING_RUNS.get(), STRUCTURE_PATHFINDING_RUNS.get(),
                NODE_TYPE_QUERIES.get(), SURFACE_QUERIES.get(), SURFACE_HITS.get());
    }

    public static String describe() {
        return snapshot().describe();
    }

    /** Resets all process-local counters. Intended for tests and debug commands. */
    public static void reset() {
        REGIONS.set(0);
        REGIONS_WITH_STRUCTURES.set(0);
        INDEX_BUILDS.set(0);
        INDEX_REBUILDS.set(0);
        STALE_REBUILDS.set(0);
        BLOCK_QUERIES.set(0);
        FLUID_QUERIES.set(0);
        STRUCTURE_BLOCK_HITS.set(0);
        STRUCTURE_FLUID_HITS.set(0);
        CANDIDATE_TESTS.set(0);
        BUDGET_EXHAUSTED.set(0);
        SKIPPED_STRUCTURES.set(0);
        PATHFINDING_RUNS.set(0);
        STRUCTURE_PATHFINDING_RUNS.set(0);
        NODE_TYPE_QUERIES.set(0);
        SURFACE_QUERIES.set(0);
        SURFACE_HITS.set(0);
    }

    public static void regionBuilt(boolean hasStructures) {
        REGIONS.incrementAndGet();
        if (hasStructures) {
            REGIONS_WITH_STRUCTURES.incrementAndGet();
        }
    }

    public static void indexBuilt(int structures, int skipped) {
        INDEX_BUILDS.incrementAndGet();
        if (skipped > 0) {
            SKIPPED_STRUCTURES.addAndGet(skipped);
        }
    }

    public static void indexRebuilt(boolean stale) {
        INDEX_REBUILDS.incrementAndGet();
        if (stale) {
            STALE_REBUILDS.incrementAndGet();
        }
    }

    public static void blockQuery() {
        BLOCK_QUERIES.incrementAndGet();
    }

    public static void fluidQuery() {
        FLUID_QUERIES.incrementAndGet();
    }

    public static void structureBlockHit() {
        STRUCTURE_BLOCK_HITS.incrementAndGet();
    }

    public static void structureFluidHit() {
        STRUCTURE_FLUID_HITS.incrementAndGet();
    }

    public static void candidateTest() {
        CANDIDATE_TESTS.incrementAndGet();
    }

    public static void budgetExhausted() {
        BUDGET_EXHAUSTED.incrementAndGet();
    }

    public static void pathfindingStarted(boolean structureAware) {
        PATHFINDING_RUNS.incrementAndGet();
        if (structureAware) {
            STRUCTURE_PATHFINDING_RUNS.incrementAndGet();
        }
    }

    public static void nodeTypeQuery() {
        NODE_TYPE_QUERIES.incrementAndGet();
    }

    public static void surfaceQuery(boolean hit) {
        SURFACE_QUERIES.incrementAndGet();
        if (hit) {
            SURFACE_HITS.incrementAndGet();
        }
    }
}
