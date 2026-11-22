package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.math.Aabb;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-navigation-region spatial index over structure voxels.
 *
 * <p>Structures are bucketed by world chunk column, so one block lookup only
 * tests structures that can possibly cover that column. Very large structures
 * (or a structure spanning an unusual number of chunks) go into a small global
 * list instead of allocating thousands of buckets.</p>
 *
 * <p>The index is mutable only for cache invalidation: every
 * {@link StructurePathLimits#VALIDATION_INTERVAL} queries it compares the
 * source stamp and rebuilds if the structure set, block content or a pose
 * changed. Rebuilds are capped; after that the index falls back to vanilla
 * world blocks for the rest of the path.</p>
 */
public final class StructurePathIndex {
    private static final StructurePathIndex EMPTY = new StructurePathIndex(
            List.of(), null, null, 0L, true);

    private final Aabb region;
    private final @Nullable StructurePathSource source;
    private final int maxQueries;
    private final Map<Long, List<StructurePathVolume>> buckets = new HashMap<>();
    private final List<StructurePathVolume> global = new ArrayList<>();
    private List<StructurePathVolume> volumes;
    private long stamp;
    private long queries;
    private int rebuilds;
    private int skippedStructures;
    private boolean disabled;
    private boolean budgetExhausted;

    private StructurePathIndex(List<StructurePathVolume> volumes, @Nullable Aabb region,
                               @Nullable StructurePathSource source, long stamp,
                               boolean disabled) {
        this(volumes, region, source, stamp, disabled,
                StructurePathLimits.MAX_QUERIES_PER_REGION);
    }

    private StructurePathIndex(List<StructurePathVolume> volumes, @Nullable Aabb region,
                               @Nullable StructurePathSource source, long stamp,
                               boolean disabled, int maxQueries) {
        this.volumes = volumes;
        this.region = region;
        this.source = source;
        this.stamp = stamp;
        this.disabled = disabled;
        this.maxQueries = Math.max(1, maxQueries);
        index(volumes);
    }

    /** Builds an index from a source for one pathfinding region. */
    public static StructurePathIndex build(StructurePathSource source, Aabb region) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (region == null) {
            throw new NullPointerException("region");
        }
        try {
            final long stamp = source.stamp();
            final List<StructurePathVolume> all = source.snapshot(region);
            final int skipped = Math.max(0, all.size() - StructurePathLimits.MAX_STRUCTURES);
            final List<StructurePathVolume> kept = skipped == 0
                    ? List.copyOf(all)
                    : List.copyOf(all.subList(0, StructurePathLimits.MAX_STRUCTURES));
            StructurePathDiagnostics.indexBuilt(kept.size(), skipped);
            return new StructurePathIndex(kept, region, source, stamp, false);
        } catch (RuntimeException failure) {
            // A broken structure source must not break vanilla pathfinding.
            return EMPTY;
        }
    }

    /** Builds an index with an explicit query budget. Mainly for tests. */
    public static StructurePathIndex build(StructurePathSource source, Aabb region,
                                           int maxQueries) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (region == null) {
            throw new NullPointerException("region");
        }
        try {
            final long stamp = source.stamp();
            final List<StructurePathVolume> all = source.snapshot(region);
            final int skipped = Math.max(0, all.size() - StructurePathLimits.MAX_STRUCTURES);
            final List<StructurePathVolume> kept = skipped == 0
                    ? List.copyOf(all)
                    : List.copyOf(all.subList(0, StructurePathLimits.MAX_STRUCTURES));
            StructurePathDiagnostics.indexBuilt(kept.size(), skipped);
            return new StructurePathIndex(kept, region, source, stamp, false, maxQueries);
        } catch (RuntimeException failure) {
            return EMPTY;
        }
    }

    /** Builds an index from pre-collected volumes. Mainly for tests. */
    public static StructurePathIndex of(List<StructurePathVolume> volumes, Aabb region) {
        if (volumes == null) {
            throw new NullPointerException("volumes");
        }
        final int skipped = Math.max(0, volumes.size() - StructurePathLimits.MAX_STRUCTURES);
        final List<StructurePathVolume> kept = skipped == 0
                ? List.copyOf(volumes)
                : List.copyOf(volumes.subList(0, StructurePathLimits.MAX_STRUCTURES));
        StructurePathDiagnostics.indexBuilt(kept.size(), skipped);
        return new StructurePathIndex(kept, region, null, 0L, false);
    }

    /** Builds an index from pre-collected volumes with an explicit query budget. */
    public static StructurePathIndex of(List<StructurePathVolume> volumes, Aabb region,
                                       int maxQueries) {
        if (volumes == null) {
            throw new NullPointerException("volumes");
        }
        final int skipped = Math.max(0, volumes.size() - StructurePathLimits.MAX_STRUCTURES);
        final List<StructurePathVolume> kept = skipped == 0
                ? List.copyOf(volumes)
                : List.copyOf(volumes.subList(0, StructurePathLimits.MAX_STRUCTURES));
        StructurePathDiagnostics.indexBuilt(kept.size(), skipped);
        return new StructurePathIndex(kept, region, null, 0L, false, maxQueries);
    }

    public boolean hasStructures() {
        return !disabled && !volumes.isEmpty();
    }

    public int size() {
        return volumes.size();
    }

    public @Nullable Aabb region() {
        return region;
    }

    public @Nullable StructurePathSource source() {
        return source;
    }

    public long stamp() {
        return stamp;
    }

    public Stats stats() {
        return new Stats(volumes.size(), queries, rebuilds, skippedStructures,
                disabled, budgetExhausted);
    }

    public record Stats(int structures, long queries, int rebuilds, int skippedStructures,
                        boolean disabled, boolean budgetExhausted) {
    }

    /**
     * Structure block at a world position, or {@code null} when no structure
     * covers it. Air inside a structure is returned as air (the caller decides
     * whether to fall back to the world state).
     */
    public @Nullable BlockState structureState(BlockPos worldPos) {
        if (!prepareQuery(true)) {
            return null;
        }
        final List<StructurePathVolume> candidates = candidates(worldPos);
        if (candidates.isEmpty()) {
            return null;
        }
        final int limit = Math.min(candidates.size(), StructurePathLimits.MAX_CANDIDATES_PER_QUERY);
        BlockState air = null;
        for (int i = 0; i < limit; i++) {
            final StructurePathVolume volume = candidates.get(i);
            StructurePathDiagnostics.candidateTest();
            final BlockState state = volume.blockStateAtWorld(worldPos);
            if (state == null) {
                continue;
            }
            if (!state.isAir()) {
                StructurePathDiagnostics.structureBlockHit();
                return state;
            }
            air = state;
        }
        return air;
    }

    /** Structure fluid at a world position, or {@code null} when outside. */
    public @Nullable FluidState structureFluid(BlockPos worldPos) {
        if (!prepareQuery(false)) {
            return null;
        }
        final List<StructurePathVolume> candidates = candidates(worldPos);
        if (candidates.isEmpty()) {
            return null;
        }
        final int limit = Math.min(candidates.size(), StructurePathLimits.MAX_CANDIDATES_PER_QUERY);
        FluidState empty = null;
        for (int i = 0; i < limit; i++) {
            final StructurePathVolume volume = candidates.get(i);
            StructurePathDiagnostics.candidateTest();
            final FluidState fluid = volume.fluidStateAtWorld(worldPos);
            if (fluid == null) {
                continue;
            }
            if (!fluid.isEmpty()) {
                StructurePathDiagnostics.structureFluidHit();
                return fluid;
            }
            empty = fluid;
        }
        return empty;
    }

    /** Whether a world column can be touched by an indexed structure. */
    public boolean affectsColumn(int x, int z) {
        if (!hasStructures()) {
            return false;
        }
        final List<StructurePathVolume> column = bucketOrGlobal(x >> 4, z >> 4);
        if (column.isEmpty()) {
            return false;
        }
        for (StructurePathVolume volume : column) {
            if (volume.affectsColumn(x, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean prepareQuery(boolean block) {
        if (!hasStructures()) {
            return false;
        }
        if (queries >= maxQueries) {
            if (!budgetExhausted) {
                budgetExhausted = true;
                StructurePathDiagnostics.budgetExhausted();
            }
            return false;
        }
        queries++;
        if (block) {
            StructurePathDiagnostics.blockQuery();
        } else {
            StructurePathDiagnostics.fluidQuery();
        }
        if ((queries % StructurePathLimits.VALIDATION_INTERVAL) == 0) {
            refreshIfStale();
        }
        return hasStructures();
    }

    private void refreshIfStale() {
        final StructurePathSource currentSource = source;
        if (currentSource == null) {
            return;
        }
        final long nextStamp;
        try {
            nextStamp = currentSource.stamp();
        } catch (RuntimeException failure) {
            disabled = true;
            return;
        }
        if (nextStamp == stamp) {
            return;
        }
        if (rebuilds >= StructurePathLimits.MAX_REBUILDS) {
            disabled = true;
            return;
        }
        rebuilds++;
        StructurePathDiagnostics.indexRebuilt(true);
        if (region == null) {
            disabled = true;
            return;
        }
        try {
            final List<StructurePathVolume> next = currentSource.snapshot(region);
            final int skipped = Math.max(0, next.size() - StructurePathLimits.MAX_STRUCTURES);
            skippedStructures = skipped;
            volumes = skipped == 0
                    ? List.copyOf(next)
                    : List.copyOf(next.subList(0, StructurePathLimits.MAX_STRUCTURES));
            stamp = nextStamp;
            index(volumes);
        } catch (RuntimeException failure) {
            disabled = true;
        }
    }

    private void index(List<StructurePathVolume> entries) {
        buckets.clear();
        global.clear();
        final List<StructurePathVolume> active = new ArrayList<>(entries.size());
        for (StructurePathVolume volume : entries) {
            if (!volume.isCurrent()) {
                continue;
            }
            active.add(volume);
            final Aabb bounds = volume.worldBounds();
            final int minChunkX = (int) Math.floor(bounds.min().x() / 16.0);
            final int maxChunkX = (int) Math.floor(bounds.max().x() / 16.0);
            final int minChunkZ = (int) Math.floor(bounds.min().z() / 16.0);
            final int maxChunkZ = (int) Math.floor(bounds.max().z() / 16.0);
            final long span = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
            if (span <= 0 || span > StructurePathLimits.MAX_CHUNK_BUCKETS_PER_STRUCTURE) {
                global.add(volume);
                continue;
            }
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    buckets.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ),
                            key -> new ArrayList<>(2)).add(volume);
                }
            }
        }
        volumes = List.copyOf(active);
    }

    private List<StructurePathVolume> candidates(BlockPos pos) {
        final List<StructurePathVolume> bucket = bucketOrGlobal(
                pos.getX() >> 4, pos.getZ() >> 4);
        return bucket;
    }

    private List<StructurePathVolume> bucketOrGlobal(int chunkX, int chunkZ) {
        final List<StructurePathVolume> bucket = buckets.get(ChunkPos.pack(chunkX, chunkZ));
        if (global.isEmpty()) {
            return bucket == null ? List.of() : bucket;
        }
        if (bucket == null || bucket.isEmpty()) {
            return global;
        }
        final List<StructurePathVolume> merged = new ArrayList<>(bucket.size() + global.size());
        merged.addAll(bucket);
        merged.addAll(global);
        return merged;
    }
}
