package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.block.BlockEntityStructureActor;
import io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.mixin.MinecraftServerAccessor;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A real, void {@code ServerLevel} used only for ticking the block logic of
 * physics bodies.
 *
 * <p>Each physics world gets one of these automatically. A structure is copied
 * into a reserved region of the void level; only chunks that actually contain
 * copied blocks are ticked. This deliberately avoids a Sable-style plot or
 * virtual-level system: the auxiliary level is a normal {@link ServerLevel} with
 * an empty {@link FlatLevelSource}, and vanilla {@code tickChunk} does the block
 * work.</p>
 *
 * <p>The level is marked {@code noSave} and is never registered in the server
 * level map. It is intended for logic, not for players or rendering.</p>
 */
public final class EmptyPhysicsLevel implements AutoCloseable {
    /** Gap between reserved structure regions, in blocks. */
    private static final int REGION_MARGIN = 16;

    /**
     * Simulation-only ticket level that makes vanilla consider a chunk
     * block-ticking without loading its neighbours. The chunk itself is loaded
     * explicitly by {@link #ensureChunk}.
     */
    private static final int BLOCK_TICKING_LEVEL =
            ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING);

    /** Upper bound for scheduled block ticks processed in one server tick. */
    public static final int MAX_BLOCK_TICKS_PER_TICK = 4_096;
    /** Upper bound for scheduled fluid ticks processed in one server tick. */
    public static final int MAX_FLUID_TICKS_PER_TICK = 4_096;
    /** Upper bound for scheduled/queued block events per server tick. */
    public static final int MAX_BLOCK_EVENTS_PER_TICK = 4_096;
    /** Upper bound for neighbour updates triggered by void block logic. */
    public static final int MAX_NEIGHBOR_UPDATES_PER_TICK = 65_536;
    /** Upper bound for block-entity NBT mirrors per server tick. */
    public static final int MAX_BLOCK_ENTITY_MIRRORS_PER_TICK = 256;
    /** Upper bound for mirrored block-state changes per server tick. */
    public static final int MAX_MIRRORED_BLOCK_CHANGES_PER_TICK = 4_096;
    /** Upper bound for queued block events before the queue drops new ones. */
    private static final int MAX_PENDING_BLOCK_EVENTS = 8_192;

    /**
     * Every live auxiliary level, indexed only for menu distance checks. The
     * list stays tiny (one level per host dimension) and is server-thread only.
     */
    private static final List<EmptyPhysicsLevel> ACTIVE = new ArrayList<>();

    /**
     * Per-tick work counters. The lists are empty when nothing changed.
     *
     * @param changedStructures structures whose volume must be reindexed
     * @param blockTicks        scheduled block ticks processed
     * @param fluidTicks        scheduled fluid ticks processed
     * @param blockEvents       block events processed
     * @param randomTicks       random block/fluid ticks processed
     * @param mirroredBlocks    block-state changes mirrored into volumes
     * @param skippedBlocks     changes outside the volume or with no region
     * @param neighborUpdates   neighbour updates processed
     * @param dropped           work dropped because a budget was exhausted
     */
    public record TickResult(List<PhysicsStructure> changedStructures,
                             int blockTicks, int fluidTicks, int blockEvents,
                             int randomTicks, int mirroredBlocks, int skippedBlocks,
                             int neighborUpdates, int dropped) {
        public static final TickResult EMPTY =
                new TickResult(List.of(), 0, 0, 0, 0, 0, 0, 0, 0);

        public int scheduledTicks() {
            return blockTicks + fluidTicks;
        }

        public String summary() {
            return "changed=" + changedStructures.size()
                    + " blockTicks=" + blockTicks
                    + " fluidTicks=" + fluidTicks
                    + " events=" + blockEvents
                    + " randomTicks=" + randomTicks
                    + " mirrored=" + mirroredBlocks
                    + " skipped=" + skippedBlocks
                    + " neighbors=" + neighborUpdates
                    + " dropped=" + dropped;
        }
    }

    /**
     * Small bounded counter used for each tick phase. Pure and testable.
     */
    public static final class TickBudget {
        private final int limit;
        private int used;

        public TickBudget(int limit) {
            this.limit = Math.max(0, limit);
        }

        public boolean tryConsume() {
            if (used >= limit) {
                return false;
            }
            used++;
            return true;
        }

        public int used() {
            return used;
        }

        public int limit() {
            return limit;
        }

        public int remaining() {
            return Math.max(0, limit - used);
        }

        public boolean exhausted() {
            return used >= limit;
        }
    }

    /**
     * A loaded block in the auxiliary level together with its live state and
     * block entity. Positions are auxiliary-level coordinates; use
     * {@link #hostBlockPos(BlockPos)} for the player-visible position.
     */
    public record LiveBlock(ServerLevel level, BlockPos pos, BlockState state,
                            @Nullable BlockEntity blockEntity) {
    }

    /**
     * Immutable view of one reserved structure region, used by the entity
     * projection pass. All coordinates are in auxiliary-level space.
     *
     * <p>{@link #localOriginX()} is the auxiliary coordinate of
     * structure-local voxel {@code (0,0,0)}. It differs from {@link #baseX()}
     * when the copied volume has a non-zero bounds minimum (for example after a
     * structure split).</p>
     *
     * @param structureId region owner body id
     * @param baseX       auxiliary coordinate that received volume bounds.minX()
     * @param baseY       auxiliary coordinate that received volume bounds.minY()
     * @param baseZ       auxiliary coordinate that received volume bounds.minZ()
     * @param volumeMinX  copied volume's minimum X
     * @param volumeMinY  copied volume's minimum Y
     * @param volumeMinZ  copied volume's minimum Z
     * @param sizeX       copied region size on X
     * @param sizeY       copied region size on Y
     * @param sizeZ       copied region size on Z
     */
    public record RegionView(long structureId,
                             int baseX, int baseY, int baseZ,
                             int volumeMinX, int volumeMinY, int volumeMinZ,
                             int sizeX, int sizeY, int sizeZ) {
        /** Auxiliary X of structure-local voxel {@code 0}. */
        public int localOriginX() {
            return baseX - volumeMinX;
        }

        /** Auxiliary Y of structure-local voxel {@code 0}. */
        public int localOriginY() {
            return baseY - volumeMinY;
        }

        /** Auxiliary Z of structure-local voxel {@code 0}. */
        public int localOriginZ() {
            return baseZ - volumeMinZ;
        }

        /** Whether a fractional auxiliary point lies inside the copied region. */
        public boolean contains(double x, double y, double z) {
            return x >= baseX && x < baseX + sizeX
                    && y >= baseY && y < baseY + sizeY
                    && z >= baseZ && z < baseZ + sizeZ;
        }

        /** Whether a chunk column overlaps this region's block bounds. */
        public boolean containsChunk(int chunkX, int chunkZ) {
            final int minChunkX = baseX >> 4;
            final int minChunkZ = baseZ >> 4;
            final int maxChunkX = (baseX + sizeX - 1) >> 4;
            final int maxChunkZ = (baseZ + sizeZ - 1) >> 4;
            return chunkX >= minChunkX && chunkX <= maxChunkX
                    && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }

        /**
         * Converts an auxiliary block position to a structure-local voxel, or
         * returns {@code null} when the position is outside this region.
         */
        public @Nullable BlockPos toLocalVoxel(BlockPos auxiliaryPos) {
            if (auxiliaryPos == null) {
                return null;
            }
            final int localX = auxiliaryPos.getX() - baseX;
            final int localY = auxiliaryPos.getY() - baseY;
            final int localZ = auxiliaryPos.getZ() - baseZ;
            if (localX < 0 || localY < 0 || localZ < 0
                    || localX >= sizeX || localY >= sizeY || localZ >= sizeZ) {
                return null;
            }
            return new BlockPos(localX + volumeMinX, localY + volumeMinY,
                    localZ + volumeMinZ);
        }

        /**
         * Converts a structure-local voxel to its auxiliary block position, or
         * returns {@code null} when the voxel is outside this region.
         */
        public @Nullable BlockPos toAuxiliaryVoxel(int x, int y, int z) {
            final int localX = x - volumeMinX;
            final int localY = y - volumeMinY;
            final int localZ = z - volumeMinZ;
            if (localX < 0 || localY < 0 || localZ < 0
                    || localX >= sizeX || localY >= sizeY || localZ >= sizeZ) {
                return null;
            }
            return new BlockPos(baseX + localX, baseY + localY, baseZ + localZ);
        }
    }

    /**
     * Pure decision helper for edit mirroring: a change matters when solidity or
     * the actual block state differs. {@code null} and air are equivalent.
     */
    public static boolean shouldMirrorState(@Nullable BlockState previous, boolean wasSolid,
                                            @Nullable BlockState next) {
        final boolean nextSolid = next != null && !next.isAir();
        return wasSolid != nextSolid
                || !Objects.equals(normalizeState(previous), normalizeState(next));
    }

    private final AuxiliaryServerLevel level;
    private final ServerLevel hostLevel;
    private final Map<Long, Integer> activeChunks = new HashMap<>();
    private final Map<Long, Region> regions = new HashMap<>();
    /** Positions whose auxiliary block state changed since the last flush. */
    private final Set<Long> changedBlocks = new HashSet<>();
    /** Structures whose volume was mutated and need a collider refresh. */
    private final Set<PhysicsStructure> dirtyStructures = new LinkedHashSet<>();
    private PhysicsStructure projectedStructure;
    /** True while our own copy/clear writes must not be mirrored back. */
    private int suppressChangeDepth;
    /** True while the tick phases run; block events are queued during this time. */
    private boolean ticking;
    private int neighborBudget;
    private long totalBlockTicks;
    private long totalFluidTicks;
    private long totalBlockEvents;
    private long totalRandomTicks;
    private long totalMirroredBlocks;
    private long totalSkippedBlocks;
    private long totalNeighborUpdates;
    private long totalDroppedWork;
    private long suppressedFailureLogs;
    private long lastFailureLogTick = Long.MIN_VALUE;
    private int nextRegionX;
    private boolean closed;

    private EmptyPhysicsLevel(AuxiliaryServerLevel level, ServerLevel hostLevel) {
        this.level = level;
        this.hostLevel = hostLevel;
        synchronized (ACTIVE) {
            ACTIVE.add(this);
        }
    }

    /**
     * @return the auxiliary level, or {@code null} if this platform/version
     *         refuses to construct it (physics still works, block ticking is
     *         skipped)
     */
    public static EmptyPhysicsLevel create(ServerLevel host) {
        try {
            final MinecraftServer server = host.getServer();
            final MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;

            final RegistryAccess registries = host.registryAccess();
            final HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
            final HolderGetter<StructureSet> structures = registries.lookupOrThrow(Registries.STRUCTURE_SET);
            final HolderGetter<PlacedFeature> features = registries.lookupOrThrow(Registries.PLACED_FEATURE);
            final Holder<Biome> plains = biomes.getOrThrow(Biomes.PLAINS);

            final FlatLevelGeneratorSettings settings = FlatLevelGeneratorSettings
                    .getDefault(biomes, structures, features)
                    .withBiomeAndLayers(List.of(), Optional.empty(), plains);
            final LevelStem stem = new LevelStem(host.dimensionTypeRegistration(), new FlatLevelSource(settings));

            final ServerLevelData data = new DerivedLevelData(server.getWorldData(),
                    server.getWorldData().overworldData());
            final Identifier hostId = host.dimension().identifier();
            final ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    Identifier.fromNamespaceAndPath(Crythst.MOD_ID,
                            "void_" + hostId.getNamespace() + "_" + hostId.getPath()));
            final AuxiliaryServerLevel voidLevel = new AuxiliaryServerLevel(
                    server, accessor.crythst$executor(), accessor.crythst$storageSource(),
                    data, key, stem, false, host.getSeed(), List.of(), false, host);
            voidLevel.noSave = true;
            final EmptyPhysicsLevel auxiliary = new EmptyPhysicsLevel(voidLevel, host);
            voidLevel.attach(auxiliary);
            return auxiliary;
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Could not create the auxiliary void level for {}; "
                    + "structure block ticking is disabled", host.dimension().identifier(), t);
            return null;
        }
    }

    /**
     * Copies a structure's solid blocks into the void level and marks its chunks
     * active. Regions never overlap, so a chunk has the same set of blocks for
     * the whole lifetime of a structure.
     */
    public void addStructure(PhysicsStructure structure, VoxelVolume volume) {
        if (closed) {
            return;
        }
        final long regionKey = structure.body().id();
        if (regions.containsKey(regionKey)) {
            removeStructure(structure);
        }

        final IntBounds3 bounds = volume.bounds();
        final int baseX = nextRegionX;
        final int baseY = 0;
        final int baseZ = 0;
        final Region region = new Region(regionKey, structure, baseX, baseY, baseZ,
                bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(),
                bounds.minX(), bounds.minY(), bounds.minZ());
        regions.put(regionKey, region);
        // Indices are monotonic and never reused: removing a region must not
        // let a later structure overlap the old region's chunks. Reserve the
        // slot before copying so a partially copied region can be removed
        // safely if block placement fails.
        nextRegionX = Math.addExact(nextRegionX, bounds.sizeX() + REGION_MARGIN);

        beginSuppressChanges();
        try {
            for (int y = bounds.minY(); y < bounds.maxY(); y++) {
                final int worldY = baseY + (y - bounds.minY());
                if (level.isOutsideBuildHeight(worldY)) {
                    continue;
                }
                for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                    for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                        if (!volume.isSolid(x, y, z)) {
                            continue;
                        }
                        final BlockState state = blockState(structure, volume, x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        final BlockPos pos = new BlockPos(
                                baseX + (x - bounds.minX()),
                                worldY,
                                baseZ + (z - bounds.minZ()));
                        ensureChunk(pos);
                        // Flag 2 skips neighbour updates: the copied region is
                        // an isolated snapshot, not a live world edit.
                        if (!level.setBlock(pos, state, 2)) {
                            continue;
                        }
                        if (state.hasBlockEntity()) {
                            region.blockEntityPositions.add(pos.asLong());
                        }
                        final long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
                        if (region.chunks.add(chunkKey)) {
                            activeChunks.merge(chunkKey, 1, Integer::sum);
                            addBlockTickingTicket(chunkKey);
                        }
                    }
                }
            }
        } finally {
            endSuppressChanges();
        }
    }

    private BlockState blockState(PhysicsStructure structure, VoxelVolume volume, int x, int y, int z) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null) {
                return state;
            }
        }
        if (volume instanceof BlockVoxelVolume blockVolume) {
            return blockVolume.blockState(x, y, z);
        }
        final BlockState sideTable = StructureBlockStates.get(structure, x, y, z);
        return sideTable == null ? Blocks.STONE.defaultBlockState() : sideTable;
    }

    /**
     * Reattaches captured block-entity NBT to the auxiliary level after a
     * structure is restored from saved data. Coordinates are structure-local
     * voxels relative to the volume bounds minimum.
     */
    public void applyBlockEntities(PhysicsStructure structure,
                                   java.util.Map<net.minecraft.core.BlockPos, net.minecraft.nbt.CompoundTag> data) {
        if (closed || data == null || data.isEmpty()) {
            return;
        }
        final Region region = regions.get(structure.body().id());
        if (region == null) {
            return;
        }
        final net.minecraft.world.level.block.state.BlockState fallback =
                Blocks.STONE.defaultBlockState();
        for (var entry : data.entrySet()) {
            final net.minecraft.core.BlockPos local = entry.getKey();
            final net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                    region.baseX + local.getX(), region.baseY + local.getY(),
                    region.baseZ + local.getZ());
            ensureChunk(pos);
            final net.minecraft.world.level.block.state.BlockState state =
                    level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            try {
                final net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                        net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                                pos, state, entry.getValue(), level.registryAccess());
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                    region.blockEntityPositions.add(pos.asLong());
                }
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Could not restore block entity at {} in auxiliary level", pos, t);
            }
        }
    }

    /**
     * Returns the immutable auxiliary-region mapping for a structure, or
     * {@code null} when the structure has no copied region.
     *
     * <p>This is the only coordinate remap a {@code StructureLevelView} needs:
     * use {@link RegionView#toAuxiliaryVoxel(int, int, int)} and
     * {@link RegionView#toLocalVoxel(BlockPos)}. Callers must not expose the
     * auxiliary coordinates publicly.</p>
     */
    public @Nullable RegionView regionView(PhysicsStructure structure) {
        if (closed || structure == null || structure.closed()) {
            return null;
        }
        final Region region = regions.get(structure.body().id());
        if (region == null || region.structure != structure) {
            return null;
        }
        return region.view();
    }

    /**
     * Captures live block-entity NBT from a structure's current region before
     * the region is replaced by {@link #addStructure}. Keys are structure-local
     * voxel positions relative to the volume bounds minimum, matching
     * {@link #applyBlockEntities}. Non-loading and safe when the auxiliary level
     * has no region for the structure.
     */
    public Map<BlockPos, CompoundTag> captureBlockEntities(PhysicsStructure structure) {
        if (closed || structure == null || structure.closed()) {
            return Map.of();
        }
        final Region region = regions.get(structure.body().id());
        if (region == null || region.blockEntityPositions.isEmpty()) {
            return Map.of();
        }
        Map<BlockPos, CompoundTag> data = null;
        for (long packed : List.copyOf(region.blockEntityPositions)) {
            final BlockPos pos = BlockPos.of(packed);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                continue;
            }
            final BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity == null) {
                continue;
            }
            if (data == null) {
                data = new LinkedHashMap<>();
            }
            data.put(new BlockPos(
                            pos.getX() - region.baseX,
                            pos.getY() - region.baseY,
                            pos.getZ() - region.baseZ),
                    blockEntity.saveWithFullMetadata(level.registryAccess()));
        }
        return data == null ? Map.of() : data;
    }

    /**
     * Reads the live auxiliary block for a structure-local voxel.
     *
     * <p>This is deliberately non-loading: it looks up the region mapping and
     * then only asks {@code ServerChunkCache#getChunkNow}. A structure whose
     * chunk has been unloaded, or that was never copied, returns {@code null}
     * instead of forcing a synchronous chunk load on the interaction thread.</p>
     *
     * @return the loaded block, or {@code null} when it is unavailable
     */
    public @Nullable LiveBlock liveBlock(PhysicsStructure structure, int x, int y, int z) {
        if (closed || structure == null || structure.closed()) {
            return null;
        }
        final Region region = regions.get(structure.body().id());
        if (region == null || region.structure != structure) {
            return null;
        }
        final IntBounds3 bounds = structure.volume().bounds();
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= region.sizeX || localY >= region.sizeY || localZ >= region.sizeZ) {
            return null;
        }
        final BlockPos pos = new BlockPos(
                region.baseX + localX, region.baseY + localY, region.baseZ + localZ);
        final LevelChunk chunk = level.getChunkSource().getChunkNow(
                pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        final BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        return new LiveBlock(level, pos, state, chunk.getBlockEntity(pos));
    }

    /**
     * Maps an auxiliary-level block position back to the host world. Used by
     * container menus, whose vanilla validity check compares the player's host
     * position with the block entity's auxiliary position.
     *
     * @return the host block position, or {@code null} when the position is
     *         not inside an active region
     */
    public @Nullable BlockPos hostBlockPos(BlockPos auxiliaryPos) {
        if (closed || auxiliaryPos == null) {
            return null;
        }
        for (Region region : regions.values()) {
            final int localX = auxiliaryPos.getX() - region.baseX;
            final int localY = auxiliaryPos.getY() - region.baseY;
            final int localZ = auxiliaryPos.getZ() - region.baseZ;
            if (localX < 0 || localY < 0 || localZ < 0
                    || localX >= region.sizeX || localY >= region.sizeY || localZ >= region.sizeZ) {
                continue;
            }
            return hostBlockPos(region, localX, localY, localZ);
        }
        return null;
    }

    private @Nullable BlockPos hostBlockPos(Region region, int localX, int localY, int localZ) {
        final PhysicsStructure structure = region.structure;
        if (structure == null || structure.closed()) {
            return null;
        }
        final IntBounds3 bounds = structure.volume().bounds();
        final Vector3d center = structure.blockCenter(
                bounds.minX() + localX, bounds.minY() + localY, bounds.minZ() + localZ);
        return BlockPos.containing(center.x, center.y, center.z);
    }

    /**
     * Resolves an auxiliary position for menu distance checks. Called by the
     * {@code Player#isWithinBlockInteractionRange} mixin; returns {@code null}
     * for ordinary host-world positions so vanilla keeps its fast path.
     */
    public static @Nullable BlockPos mapAuxiliaryToHost(Player player, BlockPos auxiliaryPos) {
        if (player == null || auxiliaryPos == null) {
            return null;
        }
        synchronized (ACTIVE) {
            for (EmptyPhysicsLevel auxiliary : ACTIVE) {
                if (!auxiliary.hostLevel.dimension().equals(player.level().dimension())) {
                    continue;
                }
                final BlockPos hostPos = auxiliary.hostBlockPos(auxiliaryPos);
                if (hostPos != null) {
                    return hostPos;
                }
            }
        }
        return null;
    }

    /**
     * Starts forwarding sounds, level events and particles from the auxiliary
     * level to the host level. Only used around an interaction dispatch:
     * otherwise auxiliary block logic stays silent, as before.
     *
     * @return whether projection was enabled
     */
    public boolean beginAudioProjection(PhysicsStructure structure) {
        if (closed || structure == null || structure.closed()) {
            return false;
        }
        projectedStructure = structure;
        level.pushProjection();
        return true;
    }

    /** Ends the projection started by {@link #beginAudioProjection}. */
    public void endAudioProjection() {
        level.popProjection();
        projectedStructure = null;
    }

    /**
     * Projects an arbitrary auxiliary-level point to the host world using the
     * owning structure's current pose and scale. Used for sounds, particles and
     * level events, including fractional particle coordinates.
     *
     * @return the world point, or {@code null} when the point is outside every
     *         live structure region
     */
    public @Nullable Vec3 projectAuxiliaryPoint(double x, double y, double z) {
        final Region region = regionAt(BlockPos.containing(x, y, z));
        if (region == null || region.structure == null || region.structure.closed()) {
            return null;
        }
        final IntBounds3 bounds = region.structure.volume().bounds();
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(
                        region.baseX - bounds.minX(), region.baseY - bounds.minY(),
                        region.baseZ - bounds.minZ());
        return StructureEntityProjection.projectPosition(region.structure.pose(),
                region.structure.blockScale(), anchor, new Vec3(x, y, z));
    }

    private void recordChangedBlock(BlockPos pos) {
        if (!suppressingChanges()) {
            changedBlocks.add(pos.asLong());
        }
    }

    public boolean suppressingChanges() {
        return suppressChangeDepth > 0;
    }

    private void beginSuppressChanges() {
        suppressChangeDepth++;
    }

    private void endSuppressChanges() {
        if (suppressChangeDepth > 0) {
            suppressChangeDepth--;
        }
    }

    /**
     * Mirrors block-state changes made by vanilla logic in the auxiliary level
     * back into the structure volume and both side tables.
     *
     * <p>Chunk access is non-loading. Changes outside the copied volume bounds
     * (for example fire spreading past the structure) are skipped and counted;
     * the volume does not grow here because that is a gameplay decision owned
     * by the interaction layer.</p>
     *
     * @return structures whose collider/body must be rebuilt, in discovery
     *         order; empty when nothing changed
     */
    public List<PhysicsStructure> flushBlockStateChanges() {
        if (closed || changedBlocks.isEmpty()) {
            return List.of();
        }
        final List<Long> changed = new ArrayList<>(changedBlocks);
        changedBlocks.clear();
        List<Long> deferred = null;
        int mirrored = 0;
        for (long packed : changed) {
            if (mirrored >= MAX_MIRRORED_BLOCK_CHANGES_PER_TICK) {
                if (deferred == null) {
                    deferred = new ArrayList<>();
                }
                deferred.add(packed);
                totalSkippedBlocks++;
                continue;
            }
            final BlockPos pos = BlockPos.of(packed);
            final Region region = regionAt(pos);
            if (region == null || region.structure == null || region.structure.closed()) {
                totalSkippedBlocks++;
                continue;
            }
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                totalSkippedBlocks++;
                continue;
            }
            final BlockState state = chunk.getBlockState(pos);
            final PhysicsStructure structure = region.structure;
            final VoxelVolume volume = structure.volume();
            final IntBounds3 bounds = volume.bounds();
            final int x = bounds.minX() + (pos.getX() - region.baseX);
            final int y = bounds.minY() + (pos.getY() - region.baseY);
            final int z = bounds.minZ() + (pos.getZ() - region.baseZ);
            if (!bounds.contains(x, y, z)) {
                totalSkippedBlocks++;
                continue;
            }
            final BlockState previous = stateAt(volume, structure, x, y, z);
            final boolean wasSolid = volume.isSolid(x, y, z);
            final boolean isSolid = !state.isAir();
            if (!shouldMirrorState(previous, wasSolid, state)) {
                continue;
            }
            final boolean volumeChanged = applyStateToVolume(volume, structure,
                    x, y, z, state, isSolid);
            if (isSolid) {
                // Fluid/fire can introduce a solid block into a chunk column
                // that had no copied block before. Keep that column ticking and
                // part of the region without re-copying the whole structure.
                ensureRegionChunk(region, pos);
            }
            if (state.hasBlockEntity()) {
                region.blockEntityPositions.add(pos.asLong());
            } else {
                region.blockEntityPositions.remove(pos.asLong());
                removeBlockEntityData(structure, bounds, x, y, z);
            }
            if (volumeChanged) {
                dirtyStructures.add(structure);
            }
            mirrored++;
        }
        if (deferred != null) {
            changedBlocks.addAll(deferred);
        }
        totalMirroredBlocks += mirrored;
        if (dirtyStructures.isEmpty()) {
            return List.of();
        }
        final List<PhysicsStructure> result = List.copyOf(dirtyStructures);
        dirtyStructures.clear();
        return result;
    }

    /**
     * Mirrors live block-entity NBT into the persistence side table. This is
     * needed because an inventory/hopper change does not necessarily change the
     * block state, so {@link #flushBlockStateChanges()} cannot see it.
     */
    public int mirrorBlockEntities() {
        if (closed || regions.isEmpty()) {
            return 0;
        }
        final TickBudget budget = new TickBudget(MAX_BLOCK_ENTITY_MIRRORS_PER_TICK);
        int mirrored = 0;
        for (Region region : regions.values()) {
            final PhysicsStructure structure = region.structure;
            if (structure == null || structure.closed() || region.blockEntityPositions.isEmpty()) {
                continue;
            }
            Map<BlockPos, CompoundTag> data = null;
            boolean changed = false;
            for (long packed : List.copyOf(region.blockEntityPositions)) {
                if (!budget.tryConsume()) {
                    totalDroppedWork++;
                    break;
                }
                final BlockPos pos = BlockPos.of(packed);
                final LevelChunk chunk = level.getChunkSource().getChunkNow(
                        pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) {
                    continue;
                }
                final BlockEntity blockEntity = chunk.getBlockEntity(pos);
                if (blockEntity == null) {
                    region.blockEntityPositions.remove(packed);
                    if (data == null) {
                        data = new LinkedHashMap<>(StructureBlockEntityData.get(structure));
                    }
                    data.remove(new BlockPos(
                            pos.getX() - region.baseX,
                            pos.getY() - region.baseY,
                            pos.getZ() - region.baseZ));
                    changed = true;
                    continue;
                }
                if (data == null) {
                    data = new LinkedHashMap<>(StructureBlockEntityData.get(structure));
                }
                final IntBounds3 bounds = structure.volume().bounds();
                final BlockPos local = new BlockPos(
                        pos.getX() - region.baseX,
                        pos.getY() - region.baseY,
                        pos.getZ() - region.baseZ);
                final CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
                if (!tag.equals(data.get(local))) {
                    data.put(local, tag);
                    changed = true;
                    mirrored++;
                }
            }
            if (changed) {
                StructureBlockEntityData.put(structure, data);
            }
        }
        return mirrored;
    }

    private static BlockState stateAt(VoxelVolume volume, PhysicsStructure structure,
                                      int x, int y, int z) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null) {
                return state;
            }
        }
        return StructureBlockStates.get(structure, x, y, z);
    }

    private static @Nullable BlockState normalizeState(@Nullable BlockState state) {
        return state == null || state.isAir() ? null : state;
    }

    /**
     * Applies one block state to the structure's editable volume and side
     * tables. Returns whether the volume itself changed.
     */
    private boolean applyStateToVolume(VoxelVolume volume, PhysicsStructure structure,
                                       int x, int y, int z, BlockState state, boolean solid) {
        BlockState previous = stateAt(volume, structure, x, y, z);
        boolean volumeChanged = false;
        if (volume instanceof RestoredStructureVolume restored) {
            final BlockPhysicsMapper.BlockProperties properties =
                    MinecraftPhysics.blockPhysics().properties(state);
            restored.setBlock(x, y, z, solid, properties.mass(), properties.material(), state);
            volumeChanged = true;
        } else if (volume instanceof MutableVoxelVolume mutable) {
            final BlockPhysicsMapper.BlockProperties properties =
                    MinecraftPhysics.blockPhysics().properties(state);
            mutable.setBlock(x, y, z, solid, properties.mass(), properties.material());
            volumeChanged = true;
        }
        if (state.isAir()) {
            StructureBlockStates.remove(structure, x, y, z);
        } else {
            StructureBlockStates.set(structure, x, y, z, state);
        }
        if (!volumeChanged) {
            // Non-editable views (BlockVoxelVolume) cannot change their
            // collision grid. The side table still reflects the live state for
            // renderers and actor discovery.
            return false;
        }
        return !Objects.equals(normalizeState(previous), normalizeState(state))
                || previous == null;
    }

    private void removeBlockEntityData(PhysicsStructure structure, IntBounds3 bounds,
                                       int x, int y, int z) {
        final Map<BlockPos, CompoundTag> current = StructureBlockEntityData.get(structure);
        if (current.isEmpty()) {
            return;
        }
        final Map<BlockPos, CompoundTag> updated = new LinkedHashMap<>(current);
        updated.remove(new BlockPos(
                x - bounds.minX(), y - bounds.minY(), z - bounds.minZ()));
        StructureBlockEntityData.put(structure, updated);
    }

    private @Nullable Region regionAt(BlockPos pos) {
        for (Region region : regions.values()) {
            final int localX = pos.getX() - region.baseX;
            final int localY = pos.getY() - region.baseY;
            final int localZ = pos.getZ() - region.baseZ;
            if (localX >= 0 && localY >= 0 && localZ >= 0
                    && localX < region.sizeX && localY < region.sizeY && localZ < region.sizeZ) {
                return region;
            }
        }
        return null;
    }

    /**
     * Discovers the physics actors among a structure's copied block entities.
     *
     * <p>Only positions recorded while copying blocks (or while restoring
     * block-entity NBT) are probed, and only chunks that are already loaded are
     * read. This never loads a chunk and never walks every block position, so it
     * is cheap enough to call after a refresh rather than every substep. The
     * resulting list is cached by {@code PhysicsStructure}.</p>
     *
     * @param structure structure whose region should be scanned; must not be
     *                  {@code null}
     * @return immutable actors in recorded order, or an empty list
     */
    public List<BlockEntityStructureActor> blockEntityActors(PhysicsStructure structure) {
        if (closed || structure == null) {
            return List.of();
        }
        final Region region = regions.get(structure.body().id());
        if (region == null || region.blockEntityPositions.isEmpty()) {
            return List.of();
        }
        List<BlockEntityStructureActor> actors = null;
        for (long packed : region.blockEntityPositions) {
            final BlockPos pos = BlockPos.of(packed);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                // The region's chunks are loaded while it is active; if one is
                // missing, skip it instead of forcing a synchronous load.
                continue;
            }
            final BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity instanceof BlockEntityStructureActor actor) {
                if (actors == null) {
                    actors = new ArrayList<>();
                }
                actors.add(actor);
            }
        }
        return actors == null ? List.of() : List.copyOf(actors);
    }

    private void ensureChunk(BlockPos pos) {
        final ChunkAccess chunk = level.getChunkSource().getChunk(
                pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true);
        if (chunk == null) {
            throw new IllegalStateException("Void level could not create chunk at " + pos);
        }
    }

    /**
     * Removes a structure's blocks and stops ticking its chunks. Safe to call
     * more than once.
     */
    public void removeStructure(PhysicsStructure structure) {
        final Region region = regions.remove(structure.body().id());
        if (region != null) {
            discard(region);
        }
    }

    /**
     * Runs the vanilla block-logic phases that matter for a structure copy,
     * without running a full {@link ServerLevel#tick}.
     *
     * <p>Phases, in vanilla order: scheduled block ticks, scheduled fluid
     * ticks, random block/fluid ticks, queued block events, block entities.
     * Weather, world border, natural spawning, raids, entity AI and chunk
     * source/loading work are intentionally not run. Only active structure
     * chunks are read; no chunk is loaded just because the void level exists.</p>
     *
     * @param randomTickSpeed random block/fluid ticks per section
     * @return structures whose volume changed and must be reindexed
     */
    public TickResult tick(int randomTickSpeed) {
        if (closed) {
            return TickResult.EMPTY;
        }
        final int beforeMirrored = (int) totalMirroredBlocks;
        final int beforeSkipped = (int) totalSkippedBlocks;
        final int beforeBlockTicks = (int) totalBlockTicks;
        final int beforeFluidTicks = (int) totalFluidTicks;
        final int beforeBlockEvents = (int) totalBlockEvents;
        final int beforeRandomTicks = (int) totalRandomTicks;
        final int beforeNeighborUpdates = (int) totalNeighborUpdates;
        final int beforeDropped = (int) totalDroppedWork;
        ticking = true;
        neighborBudget = MAX_NEIGHBOR_UPDATES_PER_TICK;
        try {
            runScheduledBlockTicks();
            runScheduledFluidTicks();
            if (!activeChunks.isEmpty()) {
                runRandomTicks(randomTickSpeed);
            }
            drainBlockEvents();
            if (!activeChunks.isEmpty()) {
                level.tickBlockEntities();
            }
            mirrorBlockEntities();
        } finally {
            ticking = false;
            neighborBudget = 0;
        }
        final List<PhysicsStructure> changed = flushBlockStateChanges();
        return new TickResult(changed,
                (int) totalBlockTicks - beforeBlockTicks,
                (int) totalFluidTicks - beforeFluidTicks,
                (int) totalBlockEvents - beforeBlockEvents,
                (int) totalRandomTicks - beforeRandomTicks,
                (int) totalMirroredBlocks - beforeMirrored,
                (int) totalSkippedBlocks - beforeSkipped,
                (int) totalNeighborUpdates - beforeNeighborUpdates,
                (int) totalDroppedWork - beforeDropped);
    }

    private void runScheduledBlockTicks() {
        final TickBudget budget = new TickBudget(MAX_BLOCK_TICKS_PER_TICK);
        level.getBlockTicks().tick(level.getGameTime(), MAX_BLOCK_TICKS_PER_TICK,
                (pos, block) -> {
                    if (!budget.tryConsume()) {
                        totalDroppedWork++;
                        return;
                    }
                    final BlockState state = level.getBlockState(pos);
                    if (state.is(block)) {
                        state.tick(level, pos, level.getRandom());
                        totalBlockTicks++;
                    }
                });
    }

    private void runScheduledFluidTicks() {
        final TickBudget budget = new TickBudget(MAX_FLUID_TICKS_PER_TICK);
        level.getFluidTicks().tick(level.getGameTime(), MAX_FLUID_TICKS_PER_TICK,
                (pos, fluid) -> {
                    if (!budget.tryConsume()) {
                        totalDroppedWork++;
                        return;
                    }
                    final FluidState state = level.getFluidState(pos);
                    if (state.getType().isSame(fluid)) {
                        state.tick(level, pos, level.getBlockState(pos));
                        totalFluidTicks++;
                    }
                });
    }

    /**
     * Vanilla {@code ServerLevel#tickChunk} minus precipitation/thunder. Doing
     * this by hand keeps weather out of the auxiliary level while preserving
     * the section random-tick semantics.
     */
    private void runRandomTicks(int randomTickSpeed) {
        if (randomTickSpeed <= 0) {
            return;
        }
        for (long chunkKey : activeChunks.keySet()) {
            final int chunkX = ChunkPos.getX(chunkKey);
            final int chunkZ = ChunkPos.getZ(chunkKey);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            final LevelChunkSection[] sections = chunk.getSections();
            final ChunkPos chunkPos = chunk.getPos();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                final LevelChunkSection section = sections[sectionIndex];
                if (section == null || !section.isRandomlyTicking()) {
                    continue;
                }
                final int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                final int baseY = SectionPos.sectionToBlockCoord(sectionY);
                for (int i = 0; i < randomTickSpeed; i++) {
                    final BlockPos pos = level.getBlockRandomPos(
                            chunkPos.getMinBlockX(), baseY, chunkPos.getMinBlockZ(), 15);
                    final BlockState state = chunk.getBlockState(pos);
                    if (state.isRandomlyTicking()) {
                        state.randomTick(level, pos, level.getRandom());
                        totalRandomTicks++;
                    }
                    final FluidState fluid = state.getFluidState();
                    if (fluid.isRandomlyTicking()) {
                        fluid.randomTick(level, pos, level.getRandom());
                        totalRandomTicks++;
                    }
                }
            }
        }
    }

    private void drainBlockEvents() {
        final TickBudget budget = new TickBudget(MAX_BLOCK_EVENTS_PER_TICK);
        while (level.hasPendingBlockEvent()) {
            if (!budget.tryConsume()) {
                level.dropPendingBlockEvents();
                totalDroppedWork++;
                return;
            }
            final BlockEventData event = level.pollPendingBlockEvent();
            if (event == null) {
                return;
            }
            if (!level.shouldTickBlocksAt(event.pos())) {
                continue;
            }
            final BlockState state = level.getBlockState(event.pos());
            if (state.is(event.block()) && state.triggerEvent(level, event.pos(),
                    event.paramA(), event.paramB())) {
                totalBlockEvents++;
            }
        }
    }

    /** True while vanilla tick phases are running. */
    public boolean isTicking() {
        return ticking;
    }

    /** Consumes one neighbour-update slot; always true outside the tick pass. */
    public boolean consumeNeighborBudget() {
        if (!ticking) {
            return true;
        }
        if (neighborBudget <= 0) {
            totalDroppedWork++;
            return false;
        }
        neighborBudget--;
        totalNeighborUpdates++;
        return true;
    }

    /** True when block-event calls should be queued instead of executed now. */
    public boolean shouldQueueBlockEvents() {
        return ticking;
    }

    /** Total scheduled/random/event work counters for diagnostics. */
    public long totalBlockTicks() {
        return totalBlockTicks;
    }

    public long totalFluidTicks() {
        return totalFluidTicks;
    }

    public long totalBlockEvents() {
        return totalBlockEvents;
    }

    public long totalRandomTicks() {
        return totalRandomTicks;
    }

    public long totalMirroredBlocks() {
        return totalMirroredBlocks;
    }

    public long totalSkippedBlocks() {
        return totalSkippedBlocks;
    }

    public long totalNeighborUpdates() {
        return totalNeighborUpdates;
    }

    public long totalDroppedWork() {
        return totalDroppedWork;
    }

    /**
     * Rate-limited failure log for tick phases. The first failure is logged
     * immediately, then at most once every 200 ticks with a suppressed count.
     */
    public void reportTickFailure(String phase, Throwable failure) {
        final long now = level.getGameTime();
        if (lastFailureLogTick == Long.MIN_VALUE
                || now - lastFailureLogTick >= 200L) {
            Crythst.LOGGER.warn("Auxiliary void-level {} failed for {} "
                            + "({} suppressed since the last report)",
                    phase, hostLevel.dimension().identifier(), suppressedFailureLogs, failure);
            lastFailureLogTick = now;
            suppressedFailureLogs = 0L;
        } else {
            suppressedFailureLogs++;
        }
    }

    public ServerLevel level() {
        return level;
    }

    public int activeChunkCount() {
        return activeChunks.size();
    }

    public int regionCount() {
        return regions.size();
    }

    /**
     * Visits entities that currently live in an active structure region.
     *
     * <p>This never loads a chunk: the region's active chunk set is the filter,
     * and {@link ServerLevel#getAllEntities()} reads the already-loaded entity
     * sections. Entities in unrelated (and therefore inactive) auxiliary chunks
     * are ignored.</p>
     *
     * @param consumer receives the region view and the entity
     * @return number of entities visited
     */
    public int collectRegionEntities(BiConsumer<RegionView, Entity> consumer) {
        if (closed || regions.isEmpty() || consumer == null) {
            return 0;
        }
        final Map<Long, Region> byChunk = new HashMap<>();
        for (Region region : regions.values()) {
            final RegionView view = region.view();
            for (long chunkKey : region.chunks) {
                byChunk.putIfAbsent(chunkKey, region);
            }
        }
        if (byChunk.isEmpty()) {
            return 0;
        }
        int visited = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            final Region region = byChunk.get(entity.chunkPosition().pack());
            if (region == null) {
                continue;
            }
            consumer.accept(region.view(), entity);
            visited++;
        }
        return visited;
    }

    /**
     * Number of entities currently inside active structure regions. Diagnostic
     * only; does not load chunks.
     */
    public int regionEntityCount() {
        return collectRegionEntities((region, entity) -> {
        });
    }

    /**
     * Removes every region and its blocks. Chunks that became empty are no
     * longer ticked.
     */
    public void clear() {
        try {
            for (Region region : List.copyOf(regions.values())) {
                discard(region);
            }
        } finally {
            regions.clear();
            activeChunks.clear();
        }
        // Keep nextRegionX monotonic: if clearing a region failed, a new
        // structure must not be placed on top of its leftover blocks.
    }

    private void discard(Region region) {
        discardRegionEntities(region);
        beginSuppressChanges();
        try {
            region.clearBlocks(level);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Error clearing auxiliary void region for body #{}",
                    region.structureId, t);
        } finally {
            endSuppressChanges();
        }
        for (long chunkKey : region.chunks) {
            final Integer count = activeChunks.get(chunkKey);
            if (count == null || count <= 1) {
                activeChunks.remove(chunkKey);
                try {
                    removeBlockTickingTicket(chunkKey);
                } catch (Throwable t) {
                    Crythst.LOGGER.debug("Could not remove void-level simulation ticket", t);
                }
            } else {
                activeChunks.put(chunkKey, count - 1);
            }
        }
    }

    /**
     * Drops entities that were left in a region because projection was
     * unavailable or failed. Without this they would accumulate forever in the
     * save-less auxiliary level.
     */
    private void discardRegionEntities(Region region) {
        if (region.chunks.isEmpty()) {
            return;
        }
        List<Entity> stale = null;
        for (Entity entity : level.getAllEntities()) {
            if (entity == null || entity.isRemoved()
                    || entity instanceof Player) {
                continue;
            }
            if (region.chunks.contains(entity.chunkPosition().pack())) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(entity);
            }
        }
        if (stale != null) {
            for (Entity entity : stale) {
                entity.discard();
            }
        }
    }

    private void ensureRegionChunk(Region region, BlockPos pos) {
        final long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (region.chunks.add(chunkKey)) {
            activeChunks.merge(chunkKey, 1, Integer::sum);
            addBlockTickingTicket(chunkKey);
        }
    }

    private void addBlockTickingTicket(long chunkKey) {
        level.getChunkSource().addTicket(
                new Ticket(TicketType.PLAYER_SIMULATION, BLOCK_TICKING_LEVEL),
                new ChunkPos(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
    }

    private void removeBlockTickingTicket(long chunkKey) {
        level.getChunkSource().removeTicketWithRadius(
                TicketType.PLAYER_SIMULATION,
                new ChunkPos(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)), 1);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        clear();
        closed = true;
        synchronized (ACTIVE) {
            ACTIVE.remove(this);
        }
        try {
            level.close();
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Error closing auxiliary void level", t);
        }
    }

    /**
     * A {@link ServerLevel} whose audio/particle broadcasts are projected to
     * the host level while an interaction is running. The auxiliary level has
     * no players, so {@code ServerLevel#playSeededSound} and
     * {@code ServerLevel#sendParticles} would otherwise reach nobody.
     */
    private static final class AuxiliaryServerLevel extends ServerLevel {
        private final ServerLevel host;
        private final ArrayDeque<BlockEventData> pendingBlockEvents = new ArrayDeque<>();
        private EmptyPhysicsLevel owner;
        private int projectionDepth;

        private AuxiliaryServerLevel(MinecraftServer server, java.util.concurrent.Executor executor,
                                     net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage,
                                     ServerLevelData data, ResourceKey<Level> dimension, LevelStem stem,
                                     boolean debug, long seed,
                                     List<net.minecraft.world.level.CustomSpawner> spawners,
                                     boolean tickTime, ServerLevel host) {
            super(server, executor, storage, data, dimension, stem, debug, seed, spawners, tickTime);
            this.host = host;
        }

        private void attach(EmptyPhysicsLevel owner) {
            this.owner = owner;
        }

        private void pushProjection() {
            projectionDepth++;
        }

        private void popProjection() {
            if (projectionDepth > 0) {
                projectionDepth--;
            }
        }

        private boolean projecting() {
            return projectionDepth > 0 && owner != null;
        }

        private @Nullable Vec3 projectPoint(double x, double y, double z) {
            return owner == null ? null : owner.projectAuxiliaryPoint(x, y, z);
        }

        private @Nullable BlockPos projectBlockPos(double x, double y, double z) {
            final Vec3 point = projectPoint(x, y, z);
            return point == null ? null : BlockPos.containing(point);
        }

        @Override
        public void playSeededSound(@Nullable Entity except, double x, double y, double z,
                                    Holder<SoundEvent> sound, SoundSource source,
                                    float volume, float pitch, long seed) {
            final Vec3 mapped = projectPoint(x, y, z);
            if (mapped != null) {
                host.playSeededSound(except, mapped.x, mapped.y, mapped.z,
                        sound, source, volume, pitch, seed);
                return;
            }
            if (projecting()) {
                // Some vanilla blocks play at the player position, which is
                // already a host coordinate.
                host.playSeededSound(except, x, y, z, sound, source, volume, pitch, seed);
            }
            // Otherwise do not leave audio in the player-less void level.
        }

        @Override
        public void playSeededSound(@Nullable Entity except, Entity sourceEntity,
                                    Holder<SoundEvent> sound, SoundSource source,
                                    float volume, float pitch, long seed) {
            if (sourceEntity instanceof Player) {
                host.playSeededSound(except, sourceEntity, sound, source, volume, pitch, seed);
                return;
            }
            final Vec3 mapped = projectPoint(sourceEntity.getX(), sourceEntity.getY(),
                    sourceEntity.getZ());
            if (mapped != null) {
                host.playSeededSound(except, mapped.x, mapped.y, mapped.z,
                        sound, source, volume, pitch, seed);
                return;
            }
            if (projecting()) {
                host.playSeededSound(except, sourceEntity, sound, source, volume, pitch, seed);
            }
        }

        @Override
        public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
            final BlockPos mapped = projectBlockPos(pos.getX() + 0.5, pos.getY() + 0.5,
                    pos.getZ() + 0.5);
            if (mapped != null) {
                host.levelEvent(source instanceof Player ? source : null, type, mapped, data);
            } else if (projecting()) {
                host.levelEvent(source instanceof Player ? source : null, type, pos, data);
            }
        }

        @Override
        public <T extends ParticleOptions> int sendParticles(T particle, boolean overrideLimiter,
                                                              boolean alwaysShow, double x, double y,
                                                              double z, int count, double xDist,
                                                              double yDist, double zDist,
                                                              double xSpeed, double ySpeed,
                                                              double zSpeed,
                                                              ClientboundLevelParticlesPacket.RandomizationType randomizationType) {
            final Vec3 mapped = projectPoint(x, y, z);
            if (mapped != null) {
                return host.sendParticles(particle, overrideLimiter, alwaysShow,
                        mapped.x, mapped.y, mapped.z,
                        count, xDist, yDist, zDist, xSpeed, ySpeed, zSpeed, randomizationType);
            }
            if (projecting()) {
                return host.sendParticles(particle, overrideLimiter, alwaysShow, x, y, z,
                        count, xDist, yDist, zDist, xSpeed, ySpeed, zSpeed, randomizationType);
            }
            return 0;
        }

        @Override
        public void blockEvent(BlockPos pos, Block block, int paramA, int paramB) {
            if (owner != null && owner.shouldQueueBlockEvents()) {
                if (pendingBlockEvents.size() >= MAX_PENDING_BLOCK_EVENTS) {
                    owner.totalDroppedWork++;
                    return;
                }
                pendingBlockEvents.add(new BlockEventData(pos, block, paramA, paramB));
                return;
            }
            final BlockState state = getBlockState(pos);
            if (state.is(block)) {
                state.triggerEvent(this, pos, paramA, paramB);
            }
        }

        private boolean hasPendingBlockEvent() {
            return !pendingBlockEvents.isEmpty();
        }

        private @Nullable BlockEventData pollPendingBlockEvent() {
            return pendingBlockEvents.poll();
        }

        private void dropPendingBlockEvents() {
            pendingBlockEvents.clear();
        }

        @Override
        public void updateNeighborsAt(BlockPos pos, Block block, Orientation orientation) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.updateNeighborsAt(pos, block, orientation);
            }
        }

        @Override
        public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block,
                                                       net.minecraft.core.Direction direction,
                                                       Orientation orientation) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.updateNeighborsAtExceptFromFacing(pos, block, direction, orientation);
            }
        }

        @Override
        public void neighborShapeChanged(net.minecraft.core.Direction direction, BlockPos pos,
                                         BlockPos neighborPos, BlockState state, int flags,
                                         int recursionLeft) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.neighborShapeChanged(direction, pos, neighborPos, state, flags, recursionLeft);
            }
        }

        @Override
        public void neighborChanged(BlockPos pos, Block block, Orientation orientation) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.neighborChanged(pos, block, orientation);
            }
        }

        @Override
        public void neighborChanged(BlockState state, BlockPos pos, Block block,
                                    Orientation orientation, boolean movedByPiston) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.neighborChanged(state, pos, block, orientation, movedByPiston);
            }
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int updateFlags, int updateLimit) {
            final boolean changed = super.setBlock(pos, state, updateFlags, updateLimit);
            if (changed && owner != null) {
                owner.recordChangedBlock(pos);
            }
            return changed;
        }
    }

    private static final class Region {
        private final long structureId;
        private final PhysicsStructure structure;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int volumeMinX;
        private final int volumeMinY;
        private final int volumeMinZ;
        private final Set<Long> chunks = new HashSet<>();
        /**
         * Absolute positions whose copied block state declares a block entity.
         * Recording them during the copy avoids probing every voxel later.
         */
        private final Set<Long> blockEntityPositions = new HashSet<>();

        private Region(long structureId, PhysicsStructure structure, int baseX, int baseY, int baseZ,
                       int sizeX, int sizeY, int sizeZ,
                       int volumeMinX, int volumeMinY, int volumeMinZ) {
            this.structureId = structureId;
            this.structure = structure;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.volumeMinX = volumeMinX;
            this.volumeMinY = volumeMinY;
            this.volumeMinZ = volumeMinZ;
        }

        private RegionView view() {
            return new RegionView(structureId, baseX, baseY, baseZ,
                    volumeMinX, volumeMinY, volumeMinZ, sizeX, sizeY, sizeZ);
        }

        private void clearBlocks(ServerLevel level) {
            final BlockState air = Blocks.AIR.defaultBlockState();
            for (int localY = 0; localY < sizeY; localY++) {
                final int y = baseY + localY;
                if (level.isOutsideBuildHeight(y)) {
                    continue;
                }
                for (int localZ = 0; localZ < sizeZ; localZ++) {
                    for (int localX = 0; localX < sizeX; localX++) {
                        final int x = baseX + localX;
                        final int z = baseZ + localZ;
                        // Only touch chunks that actually received blocks, so
                        // cleanup never creates new chunks.
                        if (!chunks.contains(ChunkPos.pack(x >> 4, z >> 4))) {
                            continue;
                        }
                        final BlockPos pos = new BlockPos(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, air, 2);
                        }
                    }
                }
            }
        }
    }
}
