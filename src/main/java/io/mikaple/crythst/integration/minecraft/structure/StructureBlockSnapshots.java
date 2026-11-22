package io.mikaple.crythst.integration.minecraft.structure;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * tick. Transient animation state that is not stored in block-entity NBT
 * (currently chest opener counts) is captured alongside the NBT and only
 * triggers a rebuild when the value actually changes.</p>
 */
public final class StructureBlockSnapshots {
    /** Maximum cells (including air) copied for one structure. */
    public static final int MAX_BLOCKS_PER_STRUCTURE = 32_768;
    /** Maximum non-air blocks retained across all snapshots. */
    public static final long MAX_TOTAL_BLOCKS = 131_072L;
    /** Maximum number of structures retained. */
    public static final int MAX_STRUCTURES = 64;
    private static final Map<ResourceKey<Level>, Map<Long, Snapshot>> SNAPSHOTS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicLong NEXT_REVISION = new AtomicLong();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

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
                // Animation state (chest opener counts, ...) is transient and
                // does not bump either revision. Capture only chest entries
                // and rebuild when the target state actually changes.
                final Map<net.minecraft.core.BlockPos, Integer> openCounts =
                        physics.auxiliaryLevel() == null
                                ? Map.of()
                                : physics.auxiliaryLevel()
                                        .captureBlockEntityOpenCounts(structure);
                // Rebuild as soon as the structure content changed, including
                // edits mirrored from the auxiliary void tick. Pose-only
                // updates do not touch contentRevision and stay free.
                if (existing != null
                        && existing.revision() == structure.contentRevision()
                        && existing.blockEntityRevision() == structure.blockEntityRevision()
                        && sameOpenCounts(existing, openCounts)) {
                    continue;
                }
                if (existing == null && size() >= MAX_STRUCTURES) {
                    continue;
                }

                final Snapshot replacement = build(structure, openCounts);
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
     * Immediately captures one structure, bypassing the end-of-tick producer.
     * Used after a split creates new body ids so the renderer does not drop the
     * structure for one frame while the next tick's snapshot pass runs.
     */
    public static void captureNow(ResourceKey<Level> dimension, PhysicsStructure structure) {
        captureNow(dimension, structure, Map.of());
    }

    /**
     * Immediately captures one structure with transient animation state, for
     * example chest opener counts. Used by callers that already have the
     * auxiliary level at hand.
     */
    public static void captureNow(ResourceKey<Level> dimension, PhysicsStructure structure,
                                  Map<net.minecraft.core.BlockPos, Integer> openCounts) {
        if (dimension == null || structure == null || structure.closed()) {
            return;
        }
        final Snapshot snapshot = build(structure, openCounts);
        if (snapshot == null) {
            return;
        }
        SNAPSHOTS.computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>())
                .put(structure.body().id(), snapshot);
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
                copy, nonAir, NEXT_REVISION.incrementAndGet(), List.of(),
                Map.of(), 0L, Map.of());
    }

    private static Snapshot build(PhysicsStructure structure,
                                  Map<net.minecraft.core.BlockPos, Integer> openCounts) {
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

        // moving_piston is rendered by its block entity, not by a block model.
        // Use the moved state so pistons remain visible in the batched
        // renderer (the animation itself is not interpolated here).
        final Map<net.minecraft.core.BlockPos, CompoundTag> blockEntities =
                StructureBlockEntityData.get(structure);
        final List<MovingBlock> movingBlocks = new ArrayList<>();

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
                    if (state.is(Blocks.MOVING_PISTON)) {
                        final CompoundTag data = blockEntities.get(
                                new net.minecraft.core.BlockPos(localX, localY, localZ));
                        if (data != null && data.contains("blockState")) {
                            final BlockState moved = BlockState.CODEC
                                    .parse(NbtOps.INSTANCE, data.get("blockState"))
                                    .result().orElse(null);
                            if (moved != null && !moved.isAir()) {
                                final Direction facing = Direction.byName(
                                        data.getStringOr("facing", "up"));
                                movingBlocks.add(new MovingBlock(
                                        localX, localY, localZ, moved,
                                        data.getFloatOr("progress", 0.0f),
                                        facing == null ? Direction.UP : facing,
                                        data.getBooleanOr("extending", false),
                                        data.getBooleanOr("source", false)));
                                // The moving-block feature renderer draws this
                                // entry with interpolation; do not bake it into
                                // the static section mesh as well.
                                state = Blocks.AIR.defaultBlockState();
                            }
                        }
                    }
                    blocks[(localY * sizeZ + localZ) * sizeX + localX] = state;
                    nonAir++;
                }
            }
        }

        if (nonAir == 0) {
            return null;
        }
        Map<net.minecraft.core.BlockPos, Integer> animations = Map.of();
        if (openCounts != null && !openCounts.isEmpty()) {
            final Map<net.minecraft.core.BlockPos, Integer> converted = new LinkedHashMap<>();
            for (Map.Entry<net.minecraft.core.BlockPos, Integer> entry : openCounts.entrySet()) {
                final net.minecraft.core.BlockPos absolute = entry.getKey();
                if (!bounds.contains(absolute.getX(), absolute.getY(), absolute.getZ())) {
                    continue;
                }
                converted.put(new net.minecraft.core.BlockPos(
                                absolute.getX() - bounds.minX(),
                                absolute.getY() - bounds.minY(),
                                absolute.getZ() - bounds.minZ()),
                        entry.getValue());
            }
            animations = Map.copyOf(converted);
        }
        return new Snapshot(structure.body().id(),
                bounds.minX(), bounds.minY(), bounds.minZ(),
                sizeX, bounds.sizeY(), sizeZ,
                blocks, nonAir, structure.contentRevision(), movingBlocks,
                blockEntities, structure.blockEntityRevision(), animations);
    }

    /**
     * Compares live absolute-voxel open counts with an existing snapshot's
     * local animation map without allocating a converted copy.
     */
    private static boolean sameOpenCounts(Snapshot snapshot,
                                          Map<net.minecraft.core.BlockPos, Integer> openCounts) {
        final Map<net.minecraft.core.BlockPos, Integer> current = snapshot.blockEntityOpenCounts();
        if (current.size() != (openCounts == null ? 0 : openCounts.size())) {
            // The live map may contain entries outside the snapshot bounds;
            // fall back to a precise comparison below if sizes merely differ
            // because the structure grew/shrunk (that also changes revision).
            if (current.isEmpty() && (openCounts == null || openCounts.isEmpty())) {
                return true;
            }
        }
        if (openCounts == null || openCounts.isEmpty()) {
            return current.isEmpty();
        }
        for (Map.Entry<net.minecraft.core.BlockPos, Integer> entry : openCounts.entrySet()) {
            final net.minecraft.core.BlockPos absolute = entry.getKey();
            if (!snapshot.containsVoxel(absolute.getX(), absolute.getY(), absolute.getZ())) {
                continue;
            }
            if (!java.util.Objects.equals(
                    snapshot.openCountAt(absolute.getX(), absolute.getY(), absolute.getZ()),
                    entry.getValue())) {
                return false;
            }
        }
        // Every snapshot entry must still be present in the live map; a removed
        // chest changes blockEntityRevision via the block-state mirror, so the
        // caller would rebuild anyway.
        return current.size() == openCounts.size();
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
        private final List<MovingBlock> movingBlocks;
        private final Map<net.minecraft.core.BlockPos, CompoundTag> blockEntities;
        private final long blockEntityRevision;
        private final Map<net.minecraft.core.BlockPos, Integer> blockEntityOpenCounts;

        private Snapshot(long bodyId, int minX, int minY, int minZ,
                         int sizeX, int sizeY, int sizeZ,
                         BlockState[] blocks, int nonAirBlocks, long revision,
                         List<MovingBlock> movingBlocks,
                         Map<net.minecraft.core.BlockPos, CompoundTag> blockEntities,
                         long blockEntityRevision,
                         Map<net.minecraft.core.BlockPos, Integer> blockEntityOpenCounts) {
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
            this.movingBlocks = List.copyOf(movingBlocks);
            this.blockEntities = Map.copyOf(blockEntities);
            this.blockEntityRevision = blockEntityRevision;
            this.blockEntityOpenCounts = blockEntityOpenCounts == null
                    ? Map.of() : Map.copyOf(blockEntityOpenCounts);
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

        /** Moving-piston entries rendered by the client feature renderer. */
        public List<MovingBlock> movingBlocks() {
            return movingBlocks;
        }

        /** Structure-local block-entity NBT, copied on the server thread. */
        public Map<net.minecraft.core.BlockPos, CompoundTag> blockEntities() {
            return blockEntities;
        }

        public long blockEntityRevision() {
            return blockEntityRevision;
        }

        /** Structure-local (snapshot-grid) transient animation state. */
        public Map<net.minecraft.core.BlockPos, Integer> blockEntityOpenCounts() {
            return blockEntityOpenCounts;
        }

        /**
         * Chest opener count at an absolute structure-local voxel, or
         * {@code null} when that voxel is not a chest (or has no live state).
         */
        public @org.jetbrains.annotations.Nullable Integer openCountAt(int x, int y, int z) {
            if (!containsVoxel(x, y, z)) {
                return null;
            }
            return blockEntityOpenCounts.get(new net.minecraft.core.BlockPos(
                    x - minX, y - minY, z - minZ));
        }

        /** Whether an absolute voxel lies inside the snapshot bounds. */
        public boolean containsVoxel(int x, int y, int z) {
            return x >= minX && x < minX + sizeX
                    && y >= minY && y < minY + sizeY
                    && z >= minZ && z < minZ + sizeZ;
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


    /**
     * One moving-piston block: the moved state, piston progress and direction.
     * Coordinates are structure-local voxels, like the snapshot grid.
     */
    public record MovingBlock(int x, int y, int z, BlockState state,
                              float progress, Direction direction,
                              boolean extending, boolean source) {
    }
}
