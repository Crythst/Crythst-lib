package io.mikaple.crythst.integration.minecraft.structure;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-thread snapshots of the block states that make up each physics
 * structure.
 *
 * <p>{@code MutableVoxelVolume} and {@code BlockVoxelVolume} are not safe to
 * read from the client render thread, and a {@code BlockVoxelVolume} is backed
 * by a live server level. This class copies the solid voxels into immutable
 * snapshots on the server thread, keyed by physics body id. The client renderer
 * only samples the structure pose and then looks up the snapshot by body id;
 * it never touches a level, a volume or a native body.</p>
 *
 * <p>In singleplayer these snapshots are the local bridge from server-thread
 * volumes to the client renderer. In multiplayer the server can live in
 * another process, so the renderer uses the network snapshot cache instead;
 * this class is not consulted for remote dimensions.</p>
 *
 * <p>Memory is bounded per structure and globally. Volumes larger than the
 * per-structure cap are not snapshotted. Snapshots are rebuilt at a throttled
 * interval so block edits appear quickly without re-reading every block every
 * tick.</p>
 */
public final class StructureBlockSnapshots {
    /** Maximum cells (including air) copied for one structure. */
    public static final int MAX_BLOCKS_PER_STRUCTURE = 32_768;
    /** Maximum non-air blocks retained across all snapshots. */
    public static final long MAX_TOTAL_BLOCKS = 131_072L;
    /** Maximum number of structures retained. */
    public static final int MAX_STRUCTURES = 64;
    /** Rebuild a snapshot every N server ticks (200 ms at 20 TPS). */
    public static final int REBUILD_INTERVAL_TICKS = 4;

    private static final Map<ResourceKey<Level>, Map<Long, Snapshot>> SNAPSHOTS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicLong NEXT_REVISION = new AtomicLong();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    private static long tickCounter;
    private static boolean cappedWarningLogged;

    private StructureBlockSnapshots() {
    }

    /**
     * Registers the snapshot producer. Safe to call more than once.
     *
     * <p>This is a static hook because {@code CrythstFabric} does not call it
     * yet. Add {@code StructureBlockSnapshots.register();} to
     * {@code CrythstFabric.onInitialize()} (or call it from
     * {@code RealStructureBlockRenderer.register()}, which does so).</p>
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(StructureBlockSnapshots::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
        Crythst.LOGGER.info("Crythst structure block snapshots registered");
    }

    /**
     * Rebuilds changed snapshots and removes snapshots for structures that no
     * longer exist. Runs on the server thread.
     */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            clear();
            return;
        }

        tickCounter++;
        final boolean rebuildThisTick = tickCounter % REBUILD_INTERVAL_TICKS == 0;
        final Set<ResourceKey<Level>> activeDimensions = new HashSet<>();
        long totalBlocks = 0L;
        for (Map<Long, Snapshot> levelSnapshots : SNAPSHOTS.values()) {
            for (Snapshot snapshot : levelSnapshots.values()) {
                totalBlocks += snapshot.nonAirBlocks();
            }
        }

        for (ServerLevel level : server.getAllLevels()) {
            final ResourceKey<Level> dimension = level.dimension();
            activeDimensions.add(dimension);

            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics == null) {
                final Map<Long, Snapshot> removed = SNAPSHOTS.remove(dimension);
                if (removed != null) {
                    for (Snapshot snapshot : removed.values()) {
                        totalBlocks -= snapshot.nonAirBlocks();
                    }
                }
                continue;
            }

            final Map<Long, Snapshot> snapshots =
                    SNAPSHOTS.computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>());
            final Set<Long> seen = new HashSet<>();

            for (PhysicsStructure structure : physics.structures()) {
                if (structure.closed()) {
                    continue;
                }
                final long bodyId = structure.body().id();
                seen.add(bodyId);

                final Snapshot existing = snapshots.get(bodyId);
                if (existing != null && !rebuildThisTick) {
                    continue;
                }
                if (existing == null && size() >= MAX_STRUCTURES) {
                    continue;
                }

                final Snapshot replacement = build(structure);
                if (replacement == null) {
                    // Oversized or empty: make sure no stale snapshot survives.
                    final Snapshot removed = snapshots.remove(bodyId);
                    if (removed != null) {
                        totalBlocks -= removed.nonAirBlocks();
                    }
                    continue;
                }

                final long keptForBody = existing == null ? 0L : existing.nonAirBlocks();
                if (totalBlocks - keptForBody + replacement.nonAirBlocks() > MAX_TOTAL_BLOCKS) {
                    if (!cappedWarningLogged) {
                        cappedWarningLogged = true;
                        Crythst.LOGGER.debug(
                                "Crythst structure block snapshots hit the {} block cap; "
                                        + "large structures keep their legacy rendering",
                                MAX_TOTAL_BLOCKS);
                    }
                    continue;
                }

                totalBlocks = totalBlocks - keptForBody + replacement.nonAirBlocks();
                snapshots.put(bodyId, replacement);
            }

            snapshots.keySet().removeIf(id -> !seen.contains(id));
            if (snapshots.isEmpty()) {
                SNAPSHOTS.remove(dimension, snapshots);
            }
        }

        SNAPSHOTS.keySet().removeIf(dimension -> !activeDimensions.contains(dimension));
    }

    /**
     * Immutable snapshot for a body in one dimension, or {@code null} when the
     * body is not tracked (not present locally, too large, or not solid).
     *
     * <p>Body ids are allocated per {@code PhysicsWorld}, so the dimension is
     * part of the lookup key to avoid collisions between dimensions.</p>
     */
    public static Snapshot get(ResourceKey<Level> dimension, long bodyId) {
        if (dimension == null) {
            return null;
        }
        final Map<Long, Snapshot> snapshots = SNAPSHOTS.get(dimension);
        return snapshots == null ? null : snapshots.get(bodyId);
    }

    /** Number of tracked structures across all dimensions, for diagnostics. */
    public static int size() {
        int size = 0;
        for (Map<Long, Snapshot> snapshots : SNAPSHOTS.values()) {
            size += snapshots.size();
        }
        return size;
    }

    /** Removes all snapshots, for example when the server stops. */
    public static void clear() {
        SNAPSHOTS.clear();
    }

    /**
     * Creates an immutable snapshot without touching a server level.
     *
     * <p>This is the supported entry point for tests and for other producers
     * that already have a block grid. The array is copied and null entries
     * become air.</p>
     *
     * @param bodyId physics body id
     * @param bounds voxel bounds (inclusive min, exclusive max)
     * @param blocks one block state per voxel, in the same order used by
     *               {@link Snapshot#stateAt(int, int, int)}
     * @return an immutable snapshot with a fresh revision
     */
    public static Snapshot create(long bodyId, IntBounds3 bounds, BlockState[] blocks) {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        if (bounds == null) {
            throw new IllegalArgumentException("bounds must not be null");
        }
        if (blocks == null || blocks.length != bounds.volume()) {
            throw new IllegalArgumentException("Block count " + (blocks == null ? 0 : blocks.length)
                    + " does not match bounds volume " + bounds.volume());
        }

        final BlockState[] copy = new BlockState[blocks.length];
        int nonAir = 0;
        for (int i = 0; i < blocks.length; i++) {
            final BlockState state = blocks[i] == null ? AIR : blocks[i];
            copy[i] = state;
            if (!state.isAir()) {
                nonAir++;
            }
        }
        return new Snapshot(bodyId, bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(),
                copy, nonAir, NEXT_REVISION.incrementAndGet());
    }

    private static Snapshot build(PhysicsStructure structure) {
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        final long cells = bounds.volume();
        if (cells <= 0 || cells > MAX_BLOCKS_PER_STRUCTURE) {
            return null;
        }

        final int sizeX = bounds.sizeX();
        final int sizeZ = bounds.sizeZ();
        final BlockState[] blocks = new BlockState[(int) cells];
        Arrays.fill(blocks, AIR);

        int nonAir = 0;

        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!volume.isSolid(x, y, z)) {
                        continue;
                    }

                    BlockState state = volume instanceof StatefulVoxelVolume stateful
                            ? stateful.blockState(x, y, z)
                            : null;
                    if (state == null || state.isAir()) {
                        // Mutable/restored/custom volumes keep authored states in
                        // the side table; consult it for every volume type.
                        state = StructureBlockStates.get(structure, x, y, z);
                    }
                    if (state == null || state.isAir()) {
                        state = STONE;
                    }

                    final int localX = x - bounds.minX();
                    final int localY = y - bounds.minY();
                    final int localZ = z - bounds.minZ();
                    blocks[(localY * sizeZ + localZ) * sizeX + localX] = state;
                    nonAir++;
                }
            }
        }

        if (nonAir == 0) {
            return null;
        }
        return new Snapshot(structure.body().id(),
                bounds.minX(), bounds.minY(), bounds.minZ(),
                sizeX, bounds.sizeY(), sizeZ,
                blocks, nonAir, NEXT_REVISION.incrementAndGet());
    }

    /**
     * One immutable copy of a structure's block grid. The array is private and
     * never mutated after construction, so it can be read from the render
     * thread without a lock.
     */
    public static final class Snapshot {
        private final long bodyId;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BlockState[] blocks;
        private final int nonAirBlocks;
        private final long revision;

        private Snapshot(long bodyId, int minX, int minY, int minZ,
                         int sizeX, int sizeY, int sizeZ,
                         BlockState[] blocks, int nonAirBlocks, long revision) {
            this.bodyId = bodyId;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blocks = blocks;
            this.nonAirBlocks = nonAirBlocks;
            this.revision = revision;
        }

        public long bodyId() {
            return bodyId;
        }

        public int minX() {
            return minX;
        }

        public int minY() {
            return minY;
        }

        public int minZ() {
            return minZ;
        }

        public int sizeX() {
            return sizeX;
        }

        public int sizeY() {
            return sizeY;
        }

        public int sizeZ() {
            return sizeZ;
        }

        public int nonAirBlocks() {
            return nonAirBlocks;
        }

        /** Monotonic revision, incremented whenever a snapshot is rebuilt. */
        public long revision() {
            return revision;
        }

        /**
         * Block state at an absolute voxel coordinate (the same coordinate
         * space used by the structure's {@link VoxelVolume}). Air is returned
         * outside the snapshot bounds.
         */
        public BlockState stateAt(int x, int y, int z) {
            if (x < minX || x >= minX + sizeX
                    || y < minY || y >= minY + sizeY
                    || z < minZ || z >= minZ + sizeZ) {
                return AIR;
            }
            final int localX = x - minX;
            final int localY = y - minY;
            final int localZ = z - minZ;
            return blocks[(localY * sizeZ + localZ) * sizeX + localX];
        }
    }
}
