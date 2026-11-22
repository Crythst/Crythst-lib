package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.block.BlockEntityStructureActor;
import io.mikaple.crythst.integration.minecraft.boundary.StructureBoundaryBridge;
import io.mikaple.crythst.integration.minecraft.entity.attachment.StructureAttachedEntities;
import io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection;
import io.mikaple.crythst.integration.minecraft.gameevent.StructureGameEvents;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.interaction.StructureFeedback;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.mixin.ChunkAccessBlockEntityAccessor;
import io.mikaple.crythst.mixin.LevelBlockEntityTickerAccessor;
import io.mikaple.crythst.mixin.LevelChunkTickAccessor;
import io.mikaple.crythst.mixin.MinecraftServerAccessor;
import io.mikaple.crythst.mixin.ServerLevelEntityManagerAccessor;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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

        /**
         * Converts a continuous structure-local voxel point to an auxiliary
         * world point. Local integer coordinates address voxel centres, which
         * are at {@code +0.5} in the auxiliary block grid.
         *
         * <p>Used by the boundary bridge to translate a projected game-event
         * point without leaking auxiliary coordinates into the public API.</p>
         */
        public Vec3 toAuxiliaryPoint(double localX, double localY, double localZ) {
            return new Vec3(
                    baseX + (localX - volumeMinX) + 0.5,
                    baseY + (localY - volumeMinY) + 0.5,
                    baseZ + (localZ - volumeMinZ) + 0.5);
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
    /**
     * Chunk object whose vanilla tick containers were last registered. Keeping
     * the object identity (not just the chunk key) matters because a chunk can
     * be unloaded and replaced under the same key; the replacement needs its
     * own containers registered and its pending ticks unpacked.
     */
    private final Map<Long, LevelChunk> initializedChunks = new HashMap<>();
    private final Map<Long, Region> regions = new HashMap<>();
    /** Positions whose auxiliary block state changed since the last flush. */
    private final Set<Long> changedBlocks = new HashSet<>();
    /** Structures whose volume was mutated and need a collider refresh. */
    private final Set<PhysicsStructure> dirtyStructures = new LinkedHashSet<>();
    private PhysicsStructure projectedStructure;
    /** True while our own copy/clear writes must not be mirrored back. */
    private int suppressChangeDepth;
    /** True while the interaction layer emits host-side break feedback itself. */
    private boolean suppressBreakFeedback;
    /** True while the tick phases run; block events are queued during this time. */
    private boolean ticking;
    private int neighborBudget;
    private long totalBlockTicks;
    private long totalScheduledPasses;
    private long totalTickPasses;
    /** Last game time for which the block-logic phases actually ran. */
    private long lastProcessedGameTime = Long.MIN_VALUE;
    private long totalFluidTicks;
    private long totalBlockEvents;
    private long totalBlockEventsQueued;
    private long totalBlockEntityFallbackTicks;
    private long totalRandomTicks;
    private long totalMirroredBlocks;
    private long totalSkippedBlocks;
    private long totalNeighborUpdates;
    private long totalDroppedWork;
    private long totalEjectedEntities;
    private long totalAdoptedAttachedEntities;
    private long totalEjectionFailures;
    private long totalChunkActivations;
    private long totalChunkReinitializations;
    private long suppressedFailureLogs;
    private long lastFailureLogTick = Long.MIN_VALUE;
    private String lastFailureDescription = "";
    private int nextRegionX;
    private boolean closed;
    private @Nullable StructureAttachedEntities attachedEntities;
    private @Nullable EntityProjector entityProjector;
    /**
     * Host-world boundary graph. {@code null} in standalone tests or when the
     * physics world has not attached one yet; all boundary reads then degrade
     * to the old void-copy behaviour.
     */
    private @Nullable StructureBoundaryBridge boundaryBridge;

    /**
     * Projects one entity out of the auxiliary level. Implemented by
     * {@code LevelPhysics}; kept as a callback so the void level does not own
     * the host-world projection policy.
     */
    @FunctionalInterface
    public interface EntityProjector {
        StructureEntityProjection.Result project(RegionView region, Entity entity);
    }

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

            final ServerLevelData data = new HostClockLevelData(
                    server.getWorldData(), server.getWorldData().overworldData(), host);
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
        addStructure(structure, volume, null);
    }

    /**
     * Copies a structure into the void level while restoring caller-captured
     * pending block/fluid ticks. Used by {@code LevelPhysics#refreshStructure},
     * which must capture the ticks <em>before</em> it removes the old region.
     *
     * @param preservedTicks ticks captured from the old region, or {@code null}
     *                       to capture from the current region when it exists
     */
    public void addStructure(PhysicsStructure structure, VoxelVolume volume,
                             @Nullable CompoundTag preservedTicks) {
        if (closed) {
            return;
        }
        final long regionKey = structure.body().id();
        // A refresh replaces the whole region. Preserve pending repeater /
        // observer / fluid delays instead of silently dropping them. Callers
        // that remove the old region first (LevelPhysics#refreshStructure) pass
        // the captured tag explicitly because there is no region left here.
        final CompoundTag previousTicks = preservedTicks != null
                ? preservedTicks
                : regions.containsKey(regionKey) ? captureScheduledTicks(structure) : null;
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
                        // A stateful volume can leave non-solid logic blocks
                        // (redstone wire, torches, water) in the side table.
                        // Only fall back to stone for a solid volume cell that
                        // carries no state at all.
                        final BlockState state = copyState(
                                blockStateOrNull(structure, volume, x, y, z),
                                volume.isSolid(x, y, z));
                        if (state == null) {
                            continue;
                        }
                        final BlockPos pos = new BlockPos(
                                baseX + (x - bounds.minX()),
                                worldY,
                                baseZ + (z - bounds.minZ()));
                        ensureChunk(pos);
                        // Copy states first with no neighbour updates. The
                        // region is not complete yet, so a live update could
                        // break unsupported blocks before their support exists.
                        if (!level.setBlock(pos, state, 2)) {
                            continue;
                        }
                        region.copiedPositions.add(pos.asLong());
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

        // Promote and register the chunk tick containers *before* waking the
        // copied blocks. LevelTicks#schedule drops requests for a chunk whose
        // container is not registered yet, so waking first would lose every
        // repeater/observer/fluid tick seeded by onPlace.
        activateActiveChunks();
        // Now that every neighbour exists and the chunks are block-ticking,
        // wake the snapshot exactly like a live loaded chunk: onPlace schedules
        // repeaters/torches/fluids, neighbour updates seed redstone and shape
        // updates, and fluid ticks make copied water/lava actually flow.
        wakeCopiedRegion(region);
        if (previousTicks != null && !previousTicks.isEmpty()) {
            applyScheduledTicks(structure, previousTicks);
        }
    }

    /**
     * Pure copy-selection rule: keep every explicit non-air state (including
     * non-colliding redstone/fluid blocks), falling back to stone only for a
     * solid cell without state information.
     *
     * @return the state to copy, or {@code null} for an empty non-solid cell
     */
    public static @Nullable BlockState copyState(@Nullable BlockState state, boolean solid) {
        if (state != null && !state.isAir()) {
            return state;
        }
        return solid ? Blocks.STONE.defaultBlockState() : null;
    }

    private @Nullable BlockState blockStateOrNull(PhysicsStructure structure, VoxelVolume volume,
                                                  int x, int y, int z) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null && !state.isAir()) {
                return state;
            }
        }
        if (volume instanceof BlockVoxelVolume blockVolume) {
            final BlockState state = blockVolume.blockState(x, y, z);
            if (state != null && !state.isAir()) {
                return state;
            }
        }
        final BlockState sideTable = StructureBlockStates.get(structure, x, y, z);
        return sideTable == null || sideTable.isAir() ? null : sideTable;
    }

    private void wakeCopiedRegion(Region region) {
        final BlockState air = Blocks.AIR.defaultBlockState();
        for (long packed : region.copiedPositions) {
            final BlockPos pos = BlockPos.of(packed);
            final BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            try {
                state.onPlace(level, pos, air, false);
                level.updateNeighborsAt(pos, state.getBlock());
                final FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    level.scheduleTick(pos, fluid.getType(),
                            Math.max(1, fluid.getType().getTickDelay(level)));
                }
            } catch (Throwable t) {
                reportTickFailure("initialize copied block " + pos.toShortString(), t);
            }
        }
    }

    /**
     * Installs the host-side attachment registry used for decorations/seats.
     * May be {@code null} in pure unit tests, in which case attached entities
     * are ejected with the ordinary projection path.
     */
    public void setAttachedEntities(@Nullable StructureAttachedEntities attachedEntities) {
        this.attachedEntities = attachedEntities;
    }

    public void setEntityProjector(@Nullable EntityProjector entityProjector) {
        this.entityProjector = entityProjector;
    }

    /**
     * Installs the host &lt;-&gt; structure boundary graph used by the auxiliary
     * level's block-state/entity/fluid/signal fallbacks.
     */
    public void setBoundaryBridge(@Nullable StructureBoundaryBridge boundaryBridge) {
        this.boundaryBridge = boundaryBridge;
    }

    public @Nullable StructureBoundaryBridge boundaryBridge() {
        return boundaryBridge;
    }

    /**
     * Pure chunk-registration decision. A chunk needs its tick containers
     * (re)registered when the currently loaded {@link LevelChunk} object is not
     * the one that was registered before. Reusing only the chunk key would skip
     * a chunk that unloaded and was replaced, leaving its new tick container
     * unregistered and silently dropping every repeater/observer schedule.
     *
     * @param registeredChunk chunk object registered last time, or {@code null}
     * @param currentChunk    chunk object currently loaded, or {@code null}
     * @return whether the current chunk requires an unpack/register pass
     */
    static boolean needsTickContainerRegistration(@Nullable Object registeredChunk,
                                                  @Nullable Object currentChunk) {
        return currentChunk != null && registeredChunk != currentChunk;
    }

    private void activateActiveChunks() {
        if (activeChunks.isEmpty()) {
            return;
        }
        // Let the vanilla distance manager promote our simulation tickets to
        // BLOCK_TICKING and complete the chunk futures. We deliberately pass
        // tickChunks=false: our own phases below run block/fluid/BE logic, while
        // spawning, entity tracking and chunk unload stay out of this level.
        try {
            level.getChunkSource().tick(() -> true, false);
        } catch (Throwable t) {
            reportTickFailure("chunk lifecycle", t);
        }
        for (long chunkKey : List.copyOf(activeChunks.keySet())) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                continue;
            }
            final LevelChunk registered = initializedChunks.get(chunkKey);
            if (needsTickContainerRegistration(registered, chunk)) {
                try {
                    chunk.unpackTicks(level.getGameTime());
                    chunk.registerTickContainerInLevel(level);
                    initializedChunks.put(chunkKey, chunk);
                    if (registered == null) {
                        totalChunkActivations++;
                    } else {
                        totalChunkReinitializations++;
                    }
                } catch (Throwable t) {
                    reportTickFailure("register chunk ticks " + chunk.getPos(), t);
                }
            }
            final FullChunkStatus status = chunk.getFullStatus();
            if (status == null || !status.isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
                try {
                    // The chunk is explicitly ticketed at BLOCK_TICKING level.
                    // Set the cached status immediately so block entities added
                    // after the copy register their tickers without waiting a
                    // frame for the promotion future.
                    chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
                } catch (Throwable t) {
                    reportTickFailure("set chunk status " + chunk.getPos(), t);
                }
            }
            primeEntitySection(chunk);
            try {
                // Idempotent: registers tickers for any block entity that was
                // copied before the entity section finished loading.
                chunk.registerAllBlockEntitiesAfterLevelLoad();
            } catch (Throwable t) {
                reportTickFailure("register block entities " + chunk.getPos(), t);
            }
        }
    }

    /**
     * Marks the active chunk's entity section as block-ticking and drains
     * pending entity loads. This does not tick entities or AI; it only lets
     * {@code LevelChunk.isTicking} return true so vanilla block-entity tickers
     * can be registered by {@link LevelChunk#registerAllBlockEntitiesAfterLevelLoad()}.
     */
    private void primeEntitySection(LevelChunk chunk) {
        try {
            final var entityManager =
                    ((ServerLevelEntityManagerAccessor) (ServerLevel) level)
                            .crythst$entityManager();
            entityManager.updateChunkStatus(chunk.getPos(), FullChunkStatus.BLOCK_TICKING);
            entityManager.processPendingLoads();
        } catch (Throwable t) {
            reportTickFailure("prime entity section " + chunk.getPos(), t);
        }
    }

    /**
     * Ejects every non-player entity from active regions. Decorative/seat
     * entities are adopted by {@link StructureAttachedEntities}; all other
     * entities use the host projection callback. Called both before block logic
     * and after block-entity work so an entity never participates in void AI or
     * stays in the void for more than one phase.
     */
    private void ejectEntities() {
        if (regions.isEmpty()) {
            return;
        }
        final List<RegionEntity> entities = new ArrayList<>();
        collectRegionEntities((region, entity) -> entities.add(new RegionEntity(region, entity)));
        for (RegionEntity entry : entities) {
            final Entity entity = entry.entity;
            if (entity == null || entity.isRemoved() || entity instanceof Player) {
                continue;
            }
            final Region region = regionById(entry.region.structureId());
            if (region == null || region.structure == null || region.structure.closed()) {
                continue;
            }
            if (attachedEntities != null
                    && attachedEntities.shouldAttachOnEjection(entity)) {
                if (attachedEntities.adoptVoidEntity(region.structure, entry.region, entity)) {
                    totalAdoptedAttachedEntities++;
                } else {
                    totalEjectionFailures++;
                }
                continue;
            }
            if (entityProjector == null) {
                totalEjectionFailures++;
                continue;
            }
            final StructureEntityProjection.Result result =
                    entityProjector.project(entry.region, entity);
            if (result == StructureEntityProjection.Result.PROJECTED
                    || result == StructureEntityProjection.Result.DUPLICATE) {
                totalEjectedEntities++;
            } else if (result == StructureEntityProjection.Result.FAILED) {
                totalEjectionFailures++;
            }
        }
    }

    private record RegionEntity(RegionView region, Entity entity) {
    }

    private @Nullable Region regionById(long structureId) {
        for (Region region : regions.values()) {
            if (region.structureId == structureId) {
                return region;
            }
        }
        return null;
    }

    /**
     * Reattaches captured block-entity NBT to the auxiliary level after a
     * structure is restored from saved data. Coordinates are structure-local
     * voxels relative to the volume bounds minimum.
     *
     * <p>Older saves (and structures split before block-entity keys were
     * normalised) may store a different origin. If the direct mapping points at
     * a block whose state does not accept the saved block-entity type, the
     * nearest matching block in the region is used instead; the side table is
     * rewritten to the resolved keys so the next save is consistent.</p>
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
        final IntBounds3 bounds = structure.volume().bounds();
        final java.util.Set<Long> claimed = new HashSet<>();
        Map<BlockPos, CompoundTag> resolved = null;
        for (var entry : data.entrySet()) {
            final BlockPos local = entry.getKey();
            final CompoundTag tag = entry.getValue();
            BlockPos pos = new BlockPos(
                    region.baseX + local.getX(), region.baseY + local.getY(),
                    region.baseZ + local.getZ());
            ensureChunk(pos);
            if (!acceptsBlockEntity(level.getBlockState(pos), tag)) {
                final BlockPos relocated = findBlockEntityPosition(
                        region, bounds, tag, pos, claimed);
                if (relocated == null) {
                    Crythst.LOGGER.debug(
                            "Could not find a matching block for saved block entity {} in region {}",
                            tag.getStringOr("id", "?"), structure.body().id());
                    continue;
                }
                pos = relocated;
            }
            final BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            try {
                final BlockEntity blockEntity = BlockEntity.loadStatic(
                        pos, state, tag, level.registryAccess());
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                    region.blockEntityPositions.add(pos.asLong());
                    claimed.add(pos.asLong());
                    if (resolved == null) {
                        resolved = new LinkedHashMap<>(data);
                    }
                    resolved.remove(local);
                    resolved.put(new BlockPos(
                            pos.getX() - region.baseX,
                            pos.getY() - region.baseY,
                            pos.getZ() - region.baseZ), tag);
                }
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Could not restore block entity at {} in auxiliary level", pos, t);
            }
        }
        if (resolved != null) {
            StructureBlockEntityData.put(structure, resolved);
            structure.bumpBlockEntityRevision();
        }
        // `Level#setBlockEntity` registers tickers through the chunk, but the
        // region may have just been copied while its entity-section status was
        // still changing. Run the activation pass now so restored furnaces,
        // hoppers and moving pistons tick on the very next void tick instead of
        // waiting for an unrelated chunk refresh.
        activateActiveChunks();
    }

    /** Whether {@code state} is a legal block for the saved block-entity id. */
    private static boolean acceptsBlockEntity(BlockState state, CompoundTag tag) {
        if (state == null || state.isAir()) {
            return false;
        }
        final Identifier id = Identifier.tryParse(tag.getStringOr("id", ""));
        if (id == null) {
            return true;
        }
        final var type = net.minecraft.core.registries.BuiltInRegistries
                .BLOCK_ENTITY_TYPE.getValue(id);
        return type == null || type.isValid(state);
    }

    /**
     * Finds the nearest block in a region that accepts the saved block-entity
     * type. Used only for legacy or pre-split keys whose origin no longer
     * matches the current volume bounds.
     */
    private @Nullable BlockPos findBlockEntityPosition(Region region, IntBounds3 bounds,
                                                       CompoundTag tag, BlockPos preferred,
                                                       java.util.Set<Long> claimed) {
        final Identifier id = Identifier.tryParse(tag.getStringOr("id", ""));
        if (id == null) {
            return null;
        }
        final var type = net.minecraft.core.registries.BuiltInRegistries
                .BLOCK_ENTITY_TYPE.getValue(id);
        if (type == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = 0; y < region.sizeY; y++) {
            for (int z = 0; z < region.sizeZ; z++) {
                for (int x = 0; x < region.sizeX; x++) {
                    final BlockPos pos = new BlockPos(
                            region.baseX + x, region.baseY + y, region.baseZ + z);
                    if (claimed.contains(pos.asLong())) {
                        continue;
                    }
                    final BlockState state = level.getBlockState(pos);
                    if (!type.isValid(state)) {
                        continue;
                    }
                    final double distance = pos.distSqr(preferred);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
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
     * Whether an auxiliary block position is inside any copied region.
     * Boundary fallbacks only apply outside every region; internal positions
     * keep their normal void-copy state.
     */
    public boolean isInsideRegion(BlockPos auxiliaryPos) {
        return auxiliaryPos != null && regionAt(auxiliaryPos) != null;
    }

    /** Host block state for an auxiliary position just outside a region. */
    public @Nullable BlockState boundaryBlockState(BlockPos auxiliaryPos) {
        return boundaryBridge == null ? null
                : boundaryBridge.hostBlockStateForAuxiliary(auxiliaryPos);
    }

    /** Host block entity for an auxiliary position just outside a region. */
    public @Nullable BlockEntity boundaryBlockEntity(BlockPos auxiliaryPos) {
        return boundaryBridge == null ? null
                : boundaryBridge.hostBlockEntityForAuxiliary(auxiliaryPos);
    }

    /** Host fluid state for an auxiliary position just outside a region. */
    public @Nullable FluidState boundaryFluidState(BlockPos auxiliaryPos) {
        return boundaryBridge == null ? null
                : boundaryBridge.hostFluidStateForAuxiliary(auxiliaryPos);
    }

    /** Host/other-structure signal for an outside auxiliary position. */
    public int boundarySignal(BlockPos auxiliaryPos, Direction localDirection) {
        return boundaryBridge == null ? 0
                : boundaryBridge.auxiliarySignal(auxiliaryPos, localDirection);
    }

    /** Host/other-structure direct signal for an outside auxiliary position. */
    public int boundaryDirectSignal(BlockPos auxiliaryPos, Direction localDirection) {
        return boundaryBridge == null ? 0
                : boundaryBridge.auxiliaryDirectSignal(auxiliaryPos, localDirection);
    }

    /** Forwards an auxiliary neighbour update to the host/other structures. */
    public void onAuxiliaryNeighborUpdate(BlockPos auxiliaryPos, Block changedBlock) {
        if (boundaryBridge != null) {
            boundaryBridge.onAuxNeighborUpdate(auxiliaryPos, changedBlock);
        }
    }

    /**
     * Forwards an auxiliary game event to the host world.
     *
     * <p>This deliberately uses {@link StructureGameEvents} rather than the
     * general boundary bridge: game events need a per-level duplicate filter
     * and a re-entrancy guard so a host listener that touches the auxiliary
     * level cannot echo the event back. The boundary bridge's game-event
     * method remains available for diagnostics and future explicit callers.</p>
     */
    public void onAuxiliaryGameEvent(Holder<GameEvent> event, Vec3 auxiliaryPosition,
                                     GameEvent.Context context) {
        StructureGameEvents.forwardFromAuxiliary(this, event, auxiliaryPosition, context);
    }

    /**
     * Returns the region containing an auxiliary block position, or
     * {@code null}. Used by the explosion bridge to map vanilla's destroyed
     * block list back to structure-local voxels without exposing region bases
     * to callers.
     */
    public @Nullable RegionView regionViewAt(@Nullable BlockPos auxiliaryPos) {
        if (closed || auxiliaryPos == null) {
            return null;
        }
        final Region region = regionAt(auxiliaryPos);
        return region == null ? null : region.view();
    }

    /**
     * Removes copied blocks selected by a host explosion from the live
     * auxiliary level. The normal edit-flush pass then mirrors the new states
     * into the structure volume, removes stale block-entity data, reindexes
     * the body and performs split/massless removal.
     *
     * @param structure   structure that owns the voxels
     * @param localVoxels absolute structure-local voxels to clear
     * @return number of copied blocks actually changed
     */
    public int removeBlocksForExplosion(PhysicsStructure structure,
                                        @Nullable List<BlockPos> localVoxels) {
        if (closed || structure == null || structure.closed()
                || localVoxels == null || localVoxels.isEmpty()) {
            return 0;
        }
        final Region region = regions.get(structure.body().id());
        if (region == null || region.structure != structure) {
            return 0;
        }
        final BlockState air = Blocks.AIR.defaultBlockState();
        int changed = 0;
        for (BlockPos local : localVoxels) {
            final BlockPos auxiliaryPos = region.view().toAuxiliaryVoxel(
                    local.getX(), local.getY(), local.getZ());
            if (auxiliaryPos == null || level.isOutsideBuildHeight(auxiliaryPos)) {
                continue;
            }
            try {
                if (level.setBlock(auxiliaryPos, air, 3)) {
                    changed++;
                }
            } catch (Throwable t) {
                reportTickFailure("explosion clear " + auxiliaryPos.toShortString(), t);
            }
        }
        return changed;
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
     * Captures transient block-entity animation state that is not part of NBT.
     *
     * <p>Chest lid progress is driven by the openers counter, not by saved
     * data, so a client snapshot alone would always render a closed lid. This
     * returns the current opener count for every chest in the region, keyed by
     * <em>absolute structure-local voxel</em> coordinates. The snapshot
     * producer converts the keys to its own local grid; renderers use them to
     * drive the synthetic client-side chest animation.</p>
     *
     * <p>Only registered block-entity positions are inspected (never a whole
     * chunk scan) and the method is non-loading. Non-chest block entities are
     * omitted, so an ordinary structure pays nothing beyond this short list.</p>
     *
     * @return immutable map of structure voxel to chest opener count; empty
     *         when the structure has no live chest block entities
     */
    public Map<BlockPos, Integer> captureBlockEntityOpenCounts(PhysicsStructure structure) {
        if (closed || structure == null || structure.closed()) {
            return Map.of();
        }
        final Region region = regions.get(structure.body().id());
        if (region == null || region.blockEntityPositions.isEmpty()) {
            return Map.of();
        }
        Map<BlockPos, Integer> data = null;
        for (long packed : List.copyOf(region.blockEntityPositions)) {
            final BlockPos pos = BlockPos.of(packed);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !(chunk.getBlockEntity(pos) instanceof ChestBlockEntity)) {
                continue;
            }
            if (data == null) {
                data = new LinkedHashMap<>();
            }
            final BlockPos local = new BlockPos(
                    structure.volume().bounds().minX() + (pos.getX() - region.baseX),
                    structure.volume().bounds().minY() + (pos.getY() - region.baseY),
                    structure.volume().bounds().minZ() + (pos.getZ() - region.baseZ));
            data.put(local, ChestBlockEntity.getOpenCount(level, pos));
        }
        return data == null ? Map.of() : Map.copyOf(data);
    }

    /**
     * Captures pending scheduled block/fluid ticks for a structure. The
     * auxiliary level is transient, so without this a repeater/observer/fluid
     * delay that was active when the world was saved is lost on re-entry.
     *
     * @return a tag suitable for {@link #applyScheduledTicks}; empty when the
     *         structure has no region or no pending ticks
     */
    public CompoundTag captureScheduledTicks(PhysicsStructure structure) {
        final CompoundTag root = new CompoundTag();
        if (closed || structure == null || structure.closed()) {
            return root;
        }
        final Region region = regions.get(structure.body().id());
        if (region == null) {
            return root;
        }
        final long now = level.getGameTime();
        final ListTag blocks = new ListTag();
        final ListTag fluids = new ListTag();
        for (long chunkKey : region.chunks) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                continue;
            }
            // A replacement chunk (or a structure copied in the same server
            // tick as a save) can still hold unpacked pending ticks. getAll()
            // only sees the live queue, so unpack first; this is idempotent.
            chunk.unpackTicks(now);
            if (chunk.getBlockTicks() instanceof LevelChunkTicks<?> rawBlocks) {
                @SuppressWarnings("unchecked")
                final LevelChunkTicks<Block> ticks = (LevelChunkTicks<Block>) rawBlocks;
                ticks.getAll().forEach(tick -> addSavedTick(blocks, region, now,
                        tick.pos(), BuiltInRegistries.BLOCK.getKey(tick.type()).toString(),
                        tick.triggerTick(), tick.priority(), tick.subTickOrder()));
            }
            if (chunk.getFluidTicks() instanceof LevelChunkTicks<?> rawFluids) {
                @SuppressWarnings("unchecked")
                final LevelChunkTicks<Fluid> ticks = (LevelChunkTicks<Fluid>) rawFluids;
                ticks.getAll().forEach(tick -> addSavedTick(fluids, region, now,
                        tick.pos(), BuiltInRegistries.FLUID.getKey(tick.type()).toString(),
                        tick.triggerTick(), tick.priority(), tick.subTickOrder()));
            }
        }
        // The sub-tick order is only meaningful relative to the other ticks in
        // the same level and tick. Persist a deterministic priority/order
        // sequence; restore assigns fresh monotonic orders, exactly like
        // vanilla's SavedTick unpacking.
        sortCapturedTickTags(blocks);
        sortCapturedTickTags(fluids);
        if (!blocks.isEmpty()) {
            root.put("Block", blocks);
        }
        if (!fluids.isEmpty()) {
            root.put("Fluid", fluids);
        }
        return root;
    }

    /**
     * Sorts persisted tick tags by the vanilla intra-tick order (priority,
     * then sub-tick). Pure helper used by capture, restore and tests.
     */
    static void sortCapturedTickTags(ListTag ticks) {
        ticks.sort(Comparator
                .comparingInt(tag -> ((CompoundTag) tag)
                        .getIntOr("Priority", TickPriority.NORMAL.getValue()))
                .thenComparingLong(tag -> ((CompoundTag) tag).getLongOr("Order", 0L))
                // Old pending ticks from separate chunks can share an order.
                // Position/type keep the restored sequence deterministic.
                .thenComparingInt(tag -> ((CompoundTag) tag).getIntOr("X", 0))
                .thenComparingInt(tag -> ((CompoundTag) tag).getIntOr("Y", 0))
                .thenComparingInt(tag -> ((CompoundTag) tag).getIntOr("Z", 0))
                .thenComparing(tag -> ((CompoundTag) tag).getStringOr("Type", "")));
    }

    private static void addSavedTick(ListTag list, Region region, long now,
                                     BlockPos pos, String type, long triggerTick,
                                     TickPriority priority, long subTickOrder) {
        final int localX = pos.getX() - region.baseX;
        final int localY = pos.getY() - region.baseY;
        final int localZ = pos.getZ() - region.baseZ;
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= region.sizeX || localY >= region.sizeY
                || localZ >= region.sizeZ) {
            return;
        }
        final CompoundTag entry = new CompoundTag();
        // Store absolute structure-local voxels so the tick survives a refresh
        // whose region/bounds origin changed.
        // region.volumeMin is the bounds minimum used when the region was
        // copied. structure.volume().bounds() may already have changed by the
        // time a split/refresh captures ticks, which misallocated them.
        entry.putInt("X", region.volumeMinX + localX);
        entry.putInt("Y", region.volumeMinY + localY);
        entry.putInt("Z", region.volumeMinZ + localZ);
        entry.putString("Type", type);
        // Delays are relative to the capture game time. This is what makes a
        // round trip through the save file independent of how long the world
        // was offline.
        entry.putLong("Delay", Math.max(0L, triggerTick - now));
        entry.putInt("Priority", priority.getValue());
        // Kept for deterministic diagnostics and old saves. Restore does not
        // reuse the value: it assigns a fresh order in the saved sequence.
        entry.putLong("Order", subTickOrder);
        list.add(entry);
    }

    /**
     * Restores pending scheduled ticks captured by
     * {@link #captureScheduledTicks}. Must run after {@link #addStructure}, so
     * the chunk tick containers exist.
     *
     * <p>Only ticks represented in the captured tag are replaced. Fresh ticks
     * seeded by {@code onPlace}/fluid re-waking are left alone; clearing every
     * tick in the region would silently delete water flow or a component that
     * started ticking while the region was copied.</p>
     */
    public void applyScheduledTicks(PhysicsStructure structure, CompoundTag root) {
        if (closed || structure == null || root == null
                || structure.closed()) {
            return;
        }
        final Region region = regions.get(structure.body().id());
        if (region == null) {
            return;
        }
        final long now = level.getGameTime();
        restoreTickList(root.getListOrEmpty("Block"), true, region, now);
        restoreTickList(root.getListOrEmpty("Fluid"), false, region, now);
    }

    private void clearRegionTicks(Region region) {
        final long now = level.getGameTime();
        for (long chunkKey : region.chunks) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                continue;
            }
            // removeIf only sees the live queue. Unpack pending ticks first so
            // a discarded region cannot leave stale work that fires when the
            // chunk container is reused.
            chunk.unpackTicks(now);
            if (chunk.getBlockTicks() instanceof LevelChunkTicks<?> rawBlocks) {
                @SuppressWarnings("unchecked")
                final LevelChunkTicks<Block> ticks = (LevelChunkTicks<Block>) rawBlocks;
                ticks.removeIf(tick -> inRegion(region, tick.pos()));
            }
            if (chunk.getFluidTicks() instanceof LevelChunkTicks<?> rawFluids) {
                @SuppressWarnings("unchecked")
                final LevelChunkTicks<Fluid> ticks = (LevelChunkTicks<Fluid>) rawFluids;
                ticks.removeIf(tick -> inRegion(region, tick.pos()));
            }
        }
    }

    private static boolean inRegion(Region region, BlockPos pos) {
        final int x = pos.getX() - region.baseX;
        final int y = pos.getY() - region.baseY;
        final int z = pos.getZ() - region.baseZ;
        return x >= 0 && y >= 0 && z >= 0
                && x < region.sizeX && y < region.sizeY && z < region.sizeZ;
    }

    private void restoreTickList(ListTag list, boolean blockTicks, Region region,
                                 long now) {
        if (list.isEmpty()) {
            return;
        }
        final ListTag ordered = list.copy();
        sortCapturedTickTags(ordered);
        for (int i = 0; i < ordered.size(); i++) {
            final CompoundTag entry = ordered.getCompoundOrEmpty(i);
            if (entry.isEmpty()) {
                continue;
            }
            final Identifier id = Identifier.tryParse(entry.getStringOr("Type", ""));
            if (id == null) {
                continue;
            }
            final BlockPos pos = new BlockPos(
                    region.baseX + entry.getIntOr("X", 0) - region.volumeMinX,
                    region.baseY + entry.getIntOr("Y", 0) - region.volumeMinY,
                    region.baseZ + entry.getIntOr("Z", 0) - region.volumeMinZ);
            ensureChunk(pos);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                continue;
            }
            final long delay = Math.max(0L, entry.getLongOr("Delay", 0L));
            final TickPriority priority = TickPriority.byValue(
                    entry.getIntOr("Priority", TickPriority.NORMAL.getValue()));
            // Rebase instead of replaying the saved order. The auxiliary level
            // counter restarts at zero after a reload; reusing an old high
            // order would make a restored repeater run after ticks scheduled
            // later in the same game tick.
            final long order = level.nextSubTickCount();
            if (blockTicks) {
                final Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                if (block == null || !(chunk.getBlockTicks() instanceof LevelChunkTicks<?> raw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                final LevelChunkTicks<Block> container = (LevelChunkTicks<Block>) raw;
                // Replace only the captured position/type. Any other pending
                // work seeded by re-waking the copied region is preserved.
                container.removeIf(existing ->
                        existing.pos().equals(pos) && existing.type() == block);
                container.schedule(
                        new ScheduledTick<>(block, pos, now + delay, priority, order));
            } else {
                final Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
                if (fluid == null || !(chunk.getFluidTicks() instanceof LevelChunkTicks<?> raw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                final LevelChunkTicks<Fluid> container = (LevelChunkTicks<Fluid>) raw;
                container.removeIf(existing ->
                        existing.pos().equals(pos) && existing.type() == fluid);
                container.schedule(
                        new ScheduledTick<>(fluid, pos, now + delay, priority, order));
            }
        }
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
        final int localX = x - region.volumeMinX;
        final int localY = y - region.volumeMinY;
        final int localZ = z - region.volumeMinZ;
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
        final Vector3d center = structure.blockCenter(
                region.volumeMinX + localX, region.volumeMinY + localY,
                region.volumeMinZ + localZ);
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
        return projectRegionPoint(region, x, y, z);
    }

    /**
     * Like {@link #projectAuxiliaryPoint(double, double, double)}, but also
     * maps a point just outside a copied region when an explosion or event is
     * close enough to belong to that structure. Used by the void-explosion
     * bridge so a blast centred on the outer face still damages host entities
     * at the transformed structure.
     *
     * @param maxDistance maximum auxiliary distance from a region's block
     *                    bounds, in blocks
     */
    public @Nullable Vec3 projectAuxiliaryPointNear(double x, double y, double z,
                                                     double maxDistance) {
        final Vec3 direct = projectAuxiliaryPoint(x, y, z);
        if (direct != null || closed || maxDistance < 0.0) {
            return direct;
        }
        Region best = null;
        double bestDistanceSquared = maxDistance * maxDistance;
        for (Region region : regions.values()) {
            if (region.structure == null || region.structure.closed()) {
                continue;
            }
            final double distanceSquared = distanceSquaredToRegion(region, x, y, z);
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = region;
            }
        }
        return projectRegionPoint(best, x, y, z);
    }

    private @Nullable Vec3 projectRegionPoint(@Nullable Region region,
                                               double x, double y, double z) {
        if (region == null || region.structure == null || region.structure.closed()) {
            return null;
        }
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(
                        region.baseX - region.volumeMinX,
                        region.baseY - region.volumeMinY,
                        region.baseZ - region.volumeMinZ);
        return StructureEntityProjection.projectPosition(region.structure.pose(),
                region.structure.blockScale(), anchor, new Vec3(x, y, z));
    }

    private static double distanceSquaredToRegion(Region region,
                                                   double x, double y, double z) {
        final double dx = axisDistance(x, region.baseX, region.baseX + region.sizeX);
        final double dy = axisDistance(y, region.baseY, region.baseY + region.sizeY);
        final double dz = axisDistance(z, region.baseZ, region.baseZ + region.sizeZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
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
            final int x = region.volumeMinX + (pos.getX() - region.baseX);
            final int y = region.volumeMinY + (pos.getY() - region.baseY);
            final int z = region.volumeMinZ + (pos.getZ() - region.baseZ);
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
                structure.bumpBlockEntityRevision();
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

    /**
     * Replaces an auxiliary-level destroy-block event with a pose-aware host
     * burst. Returns {@code false} when the event does not belong to a live
     * structure, so the caller can keep its generic projection path.
     */
    private boolean emitStructureBreakFeedback(BlockPos auxiliaryPos, BlockState state) {
        if (closed || auxiliaryPos == null || state == null || state.isAir()) {
            return false;
        }
        final Region region = regionAt(auxiliaryPos);
        if (region == null || region.structure == null || region.structure.closed()) {
            return false;
        }
        final PhysicsStructure structure = region.structure;
        final IntBounds3 bounds = structure.volume().bounds();
        final int x = region.volumeMinX + (auxiliaryPos.getX() - region.baseX);
        final int y = region.volumeMinY + (auxiliaryPos.getY() - region.baseY);
        final int z = region.volumeMinZ + (auxiliaryPos.getZ() - region.baseZ);
        if (!bounds.contains(x, y, z)) {
            return false;
        }
        StructureFeedback.breakBlock(hostLevel, structure.pose(), x, y, z, state,
                structure.blockScale(), hostLevel.getRandom());
        return true;
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
     * <p>The auxiliary level is a complete <em>block-world</em> level: active
     * chunks are promoted to block ticking and their tick containers/block
     * entities registered. Phases, in vanilla order: entity ejection, scheduled
     * block ticks, scheduled fluid ticks, random block/fluid ticks, queued
     * block events, block entities, then a second entity ejection. Global
     * weather, world-time/clock, world border, natural spawning, raids and
     * entity AI are intentionally not run; entities are ejected before any
     * entity logic could execute.</p>
     *
     * @param randomTickSpeed random block/fluid ticks per section
     * @return structures whose volume changed and must be reindexed
     */
    public TickResult tick(int randomTickSpeed) {
        if (closed) {
            return TickResult.EMPTY;
        }
        final long gameTime = level.getGameTime();
        // One block-logic pass per game time. A manual /crythst step or a
        // second callback within the same server tick must not run scheduled
        // ticks, random ticks or block entities twice; vanilla advances these
        // exactly once per game time.
        if (gameTime == lastProcessedGameTime) {
            return TickResult.EMPTY;
        }
        lastProcessedGameTime = gameTime;
        totalTickPasses++;
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
            activateActiveChunks();
            // Eject before any block/entity work so no entity AI, movement or
            // pathfinding runs in the void copy.
            ejectEntities();
            runScheduledBlockTicks();
            runScheduledFluidTicks();
            if (!activeChunks.isEmpty()) {
                runRandomTicks(randomTickSpeed);
            }
            drainBlockEvents();
            if (!activeChunks.isEmpty()) {
                level.tickBlockEntities();
                // Vanilla skips a ticker when the chunk is not BLOCK_TICKING or
                // its entity section is not loaded, and an unregistered ticker
                // is skipped even when both are true. Both gates exist in the
                // auxiliary level (its entity sections are managed by us), so
                // close the gap with a bounded direct-ticker fallback.
                runBlockEntityFallbackTicks();
                // Dispensers/droppers/block events can spawn entities. Flush
                // them before any later phase could tick them.
                ejectEntities();
            }
            if (attachedEntities != null) {
                attachedEntities.tick();
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
        totalScheduledPasses++;
        final long gameTime = level.getGameTime();
        final TickBudget budget = new TickBudget(MAX_BLOCK_TICKS_PER_TICK);
        final List<ScheduledTick<Block>> due = new ArrayList<>();
        chunkLoop:
        for (long chunkKey : activeChunks.keySet()) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null
                    || !(chunk.getBlockTicks() instanceof LevelChunkTicks<?> rawTicks)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final LevelChunkTicks<Block> ticks = (LevelChunkTicks<Block>) rawTicks;
            // Drain the chunk container directly. LevelTicks.tick additionally
            // consults DistanceManager#inBlockTickingRange and can silently
            // leave due ticks queued for an auxiliary level that is not part of
            // the host server's normal simulation graph.
            while (true) {
                final ScheduledTick<Block> tick = ticks.peek();
                if (tick == null || tick.triggerTick() > gameTime) {
                    break;
                }
                if (!budget.tryConsume()) {
                    totalDroppedWork++;
                    break chunkLoop;
                }
                ticks.poll();
                due.add(tick);
            }
        }
        // Vanilla collects every due tick first and then runs the batch in
        // intra-tick order (priority, then sub-tick). Draining chunk by chunk
        // would let an arbitrary HashMap iteration order decide which repeater
        // or observer updates first across a chunk boundary.
        sortDueTicks(due);
        for (ScheduledTick<Block> tick : due) {
            final BlockState state = level.getBlockState(tick.pos());
            if (state.is(tick.type())) {
                state.tick(level, tick.pos(), level.getRandom());
                totalBlockTicks++;
            }
        }
    }

    /**
     * Stable vanilla intra-tick order for a due-tick batch. Trigger time is
     * deliberately ignored: every tick in the batch is already due, and
     * vanilla orders them by priority and sub-tick, not by how overdue they
     * were. Pure helper used by the tick pass and tests.
     */
    static <T> void sortDueTicks(List<ScheduledTick<T>> ticks) {
        ticks.sort((left, right) -> {
            final int priority = left.priority().compareTo(right.priority());
            if (priority != 0) {
                return priority;
            }
            final int order = Long.compare(left.subTickOrder(), right.subTickOrder());
            if (order != 0) {
                return order;
            }
            // Pending ticks unpacked into different chunks can share a
            // sub-tick order. Position makes that tie deterministic instead
            // of depending on activeChunks hash iteration.
            final int x = Integer.compare(left.pos().getX(), right.pos().getX());
            if (x != 0) {
                return x;
            }
            final int y = Integer.compare(left.pos().getY(), right.pos().getY());
            return y != 0 ? y : Integer.compare(left.pos().getZ(), right.pos().getZ());
        });
    }

    private void runScheduledFluidTicks() {
        final long gameTime = level.getGameTime();
        final TickBudget budget = new TickBudget(MAX_FLUID_TICKS_PER_TICK);
        final List<ScheduledTick<Fluid>> due = new ArrayList<>();
        chunkLoop:
        for (long chunkKey : activeChunks.keySet()) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null
                    || !(chunk.getFluidTicks() instanceof LevelChunkTicks<?> rawTicks)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final LevelChunkTicks<Fluid> ticks = (LevelChunkTicks<Fluid>) rawTicks;
            while (true) {
                final ScheduledTick<Fluid> tick = ticks.peek();
                if (tick == null || tick.triggerTick() > gameTime) {
                    break;
                }
                if (!budget.tryConsume()) {
                    totalDroppedWork++;
                    break chunkLoop;
                }
                ticks.poll();
                due.add(tick);
            }
        }
        sortDueTicks(due);
        for (ScheduledTick<Fluid> tick : due) {
            final FluidState state = level.getFluidState(tick.pos());
            if (state.getType().isSame(tick.type())) {
                state.tick(level, tick.pos(), level.getBlockState(tick.pos()));
                totalFluidTicks++;
            }
        }
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

    /**
     * Ticks block entities that vanilla {@code tickBlockEntities()} skipped.
     *
     * <p>The auxiliary level does not run a normal {@code ServerLevel.tick} and
     * its entity sections are promoted manually. Vanilla's block-entity loop
     * has two independent gates for every ticker: the chunk must be
     * {@code BLOCK_TICKING} and its entity section must be loaded, and the
     * ticker itself must have been registered on the level. A newly restored or
     * newly created block entity can miss either one for a tick (or longer when
     * the entity-section load future is delayed), which stalls hoppers,
     * furnaces, moving pistons and any block entity that schedules its own
     * follow-up work.</p>
     *
     * <p>Only the skipped entries are ticked here, so an entity is never
     * double-ticked. The state ticker is invoked directly, matching vanilla's
     * validity check, while staying bounded by the active chunk list.</p>
     */
    private void runBlockEntityFallbackTicks() {
        if (activeChunks.isEmpty()) {
            return;
        }
        final boolean runsNormally = level.tickRateManager().runsNormally();
        for (long chunkKey : List.copyOf(activeChunks.keySet())) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                continue;
            }
            final LevelChunkTickAccessor accessor = (LevelChunkTickAccessor) (Object) chunk;
            final var tickers = accessor.crythst$blockEntityTickers();
            final var blockEntities = ((ChunkAccessBlockEntityAccessor) (Object) chunk)
                    .crythst$blockEntities();
            final boolean chunkTicking = chunk.getFullStatus() != null
                    && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
            for (var entry : List.copyOf(blockEntities.entrySet())) {
                final BlockEntity blockEntity = entry.getValue();
                if (blockEntity == null || blockEntity.isRemoved()) {
                    continue;
                }
                final BlockPos pos = entry.getKey();
                final boolean vanillaWouldTick = runsNormally
                        && tickers.containsKey(pos)
                        && chunkTicking
                        && level.areEntitiesLoaded(ChunkPos.pack(pos))
                        && level.shouldTickBlocksAt(pos);
                if (vanillaWouldTick) {
                    continue;
                }
                final BlockState state = chunk.getBlockState(pos);
                if (!blockEntity.getType().isValid(state)) {
                    continue;
                }
                if (tickBlockEntityNow(blockEntity, state, pos)) {
                    totalBlockEntityFallbackTicks++;
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean tickBlockEntityNow(BlockEntity blockEntity, BlockState state, BlockPos pos) {
        final BlockEntityTicker ticker;
        try {
            ticker = state.getTicker(level, (BlockEntityType) blockEntity.getType());
        } catch (Throwable t) {
            reportTickFailure("resolve block-entity ticker at " + pos.toShortString(), t);
            return false;
        }
        if (ticker == null) {
            return false;
        }
        try {
            ticker.tick(level, pos, state, blockEntity);
            return true;
        } catch (Throwable t) {
            reportTickFailure("tick block entity at " + pos.toShortString(), t);
            return false;
        }
    }

    /** True while vanilla tick phases are running. */
    public boolean isTicking() {
        return ticking;
    }

    /**
     * Suppresses aux-level destroy-block events while the interaction layer
     * emits exactly one host-side event for a redirected break. Without this,
     * creative (and wrong-tool) breaks would have no feedback at all because
     * vanilla skips {@code Block.playerDestroy} there.
     */
    public void beginSuppressBreakFeedback() {
        suppressBreakFeedback = true;
    }

    /** Ends the redirect break-feedback suppression. */
    public void endSuppressBreakFeedback() {
        suppressBreakFeedback = false;
    }

    /** Whether aux destroy-block events are currently suppressed. */
    public boolean suppressingBreakFeedback() {
        return suppressBreakFeedback;
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

    public long totalEjectedEntities() {
        return totalEjectedEntities;
    }

    public long totalAdoptedAttachedEntities() {
        return totalAdoptedAttachedEntities;
    }

    public long totalEjectionFailures() {
        return totalEjectionFailures;
    }

    /** Cumulative chunks whose tick containers were registered. */
    public long totalChunkActivations() {
        return totalChunkActivations;
    }

    /** Activated chunks whose {@code LevelChunk} object was replaced. */
    public long totalChunkReinitializations() {
        return totalChunkReinitializations;
    }

    /** Cumulative block events queued for the deferred event phase. */
    public long totalBlockEventsQueued() {
        return totalBlockEventsQueued;
    }

    /**
     * Actual state-ticker invocations by the fallback pass. Vanilla's own
     * {@code tickBlockEntities()} calls are not observable here; the
     * registration/loaded counters show whether vanilla could run them.
     */
    public long totalBlockEntityFallbackTicks() {
        return totalBlockEntityFallbackTicks;
    }

    /**
     * Live block entities recorded in active regions. Reads only loaded chunks
     * and is intended for commands/diagnostics.
     */
    public int activeBlockEntityCount() {
        int count = 0;
        for (Region region : regions.values()) {
            for (long packed : region.blockEntityPositions) {
                final BlockPos pos = BlockPos.of(packed);
                final LevelChunk chunk = level.getChunkSource().getChunkNow(
                        pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk != null && chunk.getBlockEntity(pos) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Block-entity tickers registered on the chunks of active regions. A ticker
     * can still be skipped by vanilla when the chunk/entity section is not
     * ticking, which is exactly what the loaded-status diagnostic exposes.
     */
    public int activeBlockEntityTickerCount() {
        int count = 0;
        for (long chunkKey : activeChunks.keySet()) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk != null) {
                count += ((LevelChunkTickAccessor) (Object) chunk)
                        .crythst$blockEntityTickers().size();
            }
        }
        return count;
    }

    /** Tickers in the auxiliary level's global (non-pending) list. */
    public int globalBlockEntityTickerCount() {
        return ((LevelBlockEntityTickerAccessor) (Object) level)
                .crythst$blockEntityTickers().size();
    }

    /** Tickers queued while the previous block-entity pass was running. */
    public int pendingBlockEntityTickerCount() {
        return ((LevelBlockEntityTickerAccessor) (Object) level)
                .crythst$pendingBlockEntityTickers().size();
    }

    /** Active chunks whose {@code LevelChunk} reports its loaded flag. */
    public int loadedActiveChunkCount() {
        int count = 0;
        for (long chunkKey : activeChunks.keySet()) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk != null && ((LevelChunkTickAccessor) (Object) chunk).crythst$isLoaded()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sections in active chunks that carry random-ticking blocks/fluids. This
     * explains a zero random-tick counter for redstone-only structures.
     */
    public int randomTickingSectionCount() {
        int count = 0;
        for (long chunkKey : activeChunks.keySet()) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                continue;
            }
            for (LevelChunkSection section : chunk.getSections()) {
                if (section != null && section.isRandomlyTicking()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Pending scheduled block ticks across all registered chunk containers. */
    public int pendingBlockTicks() {
        return level.getBlockTicks().count();
    }

    /** Pending scheduled fluid ticks across all registered chunk containers. */
    public int pendingFluidTicks() {
        return level.getFluidTicks().count();
    }

    /**
     * One-line diagnostics for the block-entity gate: registration count,
     * loaded chunk count and whether the entity sections were promoted. This
     * distinguishes "no ticker registered" from "ticker registered but chunk
     * not ticking".
     */
    public String blockEntityDiagnostics() {
        return "blockEntities=" + activeBlockEntityCount()
                + " beTickers=" + activeBlockEntityTickerCount()
                + " globalBeTickers=" + globalBlockEntityTickerCount()
                + " pendingBeTickers=" + pendingBlockEntityTickerCount()
                + " loadedChunks=" + loadedActiveChunkCount() + "/" + activeChunks.size()
                + " randomSections=" + randomTickingSectionCount()
                + " chunkActivations=" + totalChunkActivations
                + " chunkReinitializations=" + totalChunkReinitializations
                + " eventsQueued=" + totalBlockEventsQueued;
    }

    public @Nullable StructureAttachedEntities attachedEntities() {
        return attachedEntities;
    }

    /**
     * Cumulative void-level diagnostics, safe for commands and logs.
     */
    public String status() {
        return "levelCreated=true"
                + " regions=" + regionCount()
                + " activeChunks=" + activeChunkCount()
                + " tickPasses=" + totalTickPasses
                + " scheduledBlockTicks=" + totalBlockTicks
                + " scheduledFluidTicks=" + totalFluidTicks
                + " pendingBlockTicks=" + pendingBlockTicks()
                + " pendingFluidTicks=" + pendingFluidTicks()
                + " randomTicks=" + totalRandomTicks
                + " blockEvents=" + totalBlockEvents
                + " blockEventsQueued=" + totalBlockEventsQueued
                + " blockEntityFallbackTicks=" + totalBlockEntityFallbackTicks
                + " neighbors=" + totalNeighborUpdates
                + " mirroredBlocks=" + totalMirroredBlocks
                + " skippedBlocks=" + totalSkippedBlocks
                + " droppedWork=" + totalDroppedWork
                + " entitiesEjected=" + totalEjectedEntities
                + " attachedAdopted=" + totalAdoptedAttachedEntities
                + " ejectionFailures=" + totalEjectionFailures
                + " " + blockEntityDiagnostics()
                + " " + tickDiagnostics()
                + " " + (boundaryBridge == null ? "boundary=inactive" : boundaryBridge.status())
                + " lastFailure=" + (lastFailureDescription.isEmpty()
                        ? "none" : lastFailureDescription);
    }

    /**
     * Focused diagnostics for the chunk-ticket/tick-list path. Prints the game
     * time and the block-ticking status of one active chunk, which distinguishes
     * a missing ticket from a frozen clock or lost scheduled ticks.
     */
    public String tickDiagnostics() {
        final long now = level.getGameTime();
        if (activeChunks.isEmpty()) {
            return "gameTime=" + now
                    + " lastProcessedGameTime=" + formatGameTime(lastProcessedGameTime)
                    + " tickPasses=" + totalTickPasses
                    + " tickChunk=no-chunks";
        }
        int simulated = 0;
        int activePendingBlocks = 0;
        int activePendingFluids = 0;
        long firstTrigger = Long.MAX_VALUE;
        TickPriority firstPriority = TickPriority.NORMAL;
        long firstSubTick = Long.MAX_VALUE;
        String firstPos = "-";
        for (long key : activeChunks.keySet()) {
            if (level.getChunkSource().chunkMap.getDistanceManager()
                    .inBlockTickingRange(key)) {
                simulated++;
            }
            final LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) {
                activePendingBlocks += chunk.getBlockTicks().count();
                activePendingFluids += chunk.getFluidTicks().count();
                if (chunk.getBlockTicks()
                        instanceof net.minecraft.world.ticks.LevelChunkTicks<?> ticks) {
                    final var peek = ticks.peek();
                    if (peek != null && isEarlier(peek.triggerTick(), peek.priority(),
                            peek.subTickOrder(), firstTrigger, firstPriority, firstSubTick)) {
                        firstTrigger = peek.triggerTick();
                        firstPriority = peek.priority();
                        firstSubTick = peek.subTickOrder();
                        firstPos = peek.pos().toShortString();
                    }
                }
            }
        }
        final long key = activeChunks.keySet().iterator().next();
        final LevelChunk chunk = level.getChunkSource().getChunkNow(
                ChunkPos.getX(key), ChunkPos.getZ(key));
        final long nextDelay = firstTrigger == Long.MAX_VALUE ? -1L : firstTrigger - now;
        return "gameTime=" + now
                + " lastProcessedGameTime=" + formatGameTime(lastProcessedGameTime)
                + " tickPasses=" + totalTickPasses
                + " scheduledPasses=" + totalScheduledPasses
                + " simulated=" + simulated + "/" + activeChunks.size()
                + " activePendingBlocks=" + activePendingBlocks
                + " activePendingFluids=" + activePendingFluids
                + " nextDue=" + (firstTrigger == Long.MAX_VALUE ? "-" : firstTrigger)
                + " nextDelay=" + nextDelay
                + " nextPriority=" + (firstTrigger == Long.MAX_VALUE ? "-" : firstPriority)
                + " nextSubTick=" + (firstTrigger == Long.MAX_VALUE ? "-" : firstSubTick)
                + " firstPos=" + firstPos
                + " chunkStatus=" + (chunk == null ? "unloaded" : chunk.getFullStatus())
                + " activeTickets=" + level.getChunkSource().hasActiveTickets();
    }

    /**
     * Per-region scheduled-tick diagnostics for {@code /crythst voiddebug}.
     * Reads only already-loaded chunks and never schedules or loads work.
     */
    public String tickScheduleDiagnostics() {
        final long now = level.getGameTime();
        return "gameTime=" + now
                + " lastProcessedGameTime=" + formatGameTime(lastProcessedGameTime)
                + " tickPasses=" + totalTickPasses
                + " scheduledPasses=" + totalScheduledPasses
                + " regionPending(block/fluid)=" + regionPendingDiagnostics();
    }

    private String regionPendingDiagnostics() {
        if (regions.isEmpty()) {
            return "-";
        }
        final StringBuilder result = new StringBuilder();
        for (Region region : regions.values()) {
            int blocks = 0;
            int fluids = 0;
            for (long chunkKey : region.chunks) {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(
                        ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
                if (chunk == null) {
                    continue;
                }
                blocks += chunk.getBlockTicks().count();
                fluids += chunk.getFluidTicks().count();
            }
            if (!result.isEmpty()) {
                result.append(',');
            }
            result.append(region.structureId)
                    .append(':').append(blocks)
                    .append('/').append(fluids);
        }
        return result.toString();
    }

    private static boolean isEarlier(long trigger, TickPriority priority, long subTick,
                                     long bestTrigger, TickPriority bestPriority, long bestSubTick) {
        if (bestTrigger == Long.MAX_VALUE) {
            return true;
        }
        if (trigger != bestTrigger) {
            return trigger < bestTrigger;
        }
        final int byPriority = priority.compareTo(bestPriority);
        return byPriority != 0 ? byPriority < 0 : subTick < bestSubTick;
    }

    private static String formatGameTime(long gameTime) {
        return gameTime == Long.MIN_VALUE ? "-" : Long.toString(gameTime);
    }

    /**
     * Rate-limited failure log for tick phases. The first failure is logged
     * immediately, then at most once every 200 ticks with a suppressed count.
     */
    public void reportTickFailure(String phase, Throwable failure) {
        final long now = level.getGameTime();
        lastFailureDescription = phase + ": " + (failure == null
                ? "unknown" : failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
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

    /**
     * Host level that owns this auxiliary copy. Game-event and explosion
     * bridges use it to map auxiliary coordinates back to the visible world.
     */
    public ServerLevel hostLevel() {
        return hostLevel;
    }

    /** Whether this auxiliary level has been closed. */
    public boolean closed() {
        return closed;
    }

    /**
     * Resolves the owner of an auxiliary {@link Level}, or {@code null} for a
     * normal host level. This is the only public way bridge code needs to
     * distinguish "explosion happened in a structure copy" from "explosion
     * happened in the real world".
     */
    public static @Nullable EmptyPhysicsLevel ofLevel(@Nullable Level level) {
        if (level instanceof AuxiliaryServerLevel auxiliary
                && auxiliary.owner != null && !auxiliary.owner.closed) {
            return auxiliary.owner;
        }
        return null;
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
            initializedChunks.clear();
        }
        // Keep nextRegionX monotonic: if clearing a region failed, a new
        // structure must not be placed on top of its leftover blocks.
    }

    private void discard(Region region) {
        discardRegionEntities(region);
        // Remove this region's pending scheduled work before its blocks go
        // away. The chunk container itself can outlive an inactive region (and
        // is saved with the chunk), so leaving overdue repeater/observer ticks
        // behind makes them fire unexpectedly the next time the chunk is
        // activated.
        try {
            clearRegionTicks(region);
        } catch (Throwable t) {
            Crythst.LOGGER.debug("Could not clear void-level region ticks", t);
        }
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
                initializedChunks.remove(chunkKey);
                try {
                    // Remove the chunk's tick containers from the level tick
                    // lists as well. Leaving them registered would keep
                    // scheduled ticks for a no-longer-simulated chunk forever.
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(
                            ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
                    if (chunk != null) {
                        chunk.unregisterTickContainerFromLevel(level);
                    }
                    removeBlockTickingTicket(chunkKey);
                } catch (Throwable t) {
                    Crythst.LOGGER.debug("Could not remove void-level chunk tickets", t);
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
        final ChunkPos pos = new ChunkPos(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        // PLAYER_SIMULATION updates DistanceManager's simulation tracker, which
        // is what LevelTicks/ServerLevel#shouldTickBlocksAt consult. It does not
        // load the chunk by itself, so add a loading ticket at the same level
        // to promote the holder to FullChunkStatus.BLOCK_TICKING.
        level.getChunkSource().addTicket(
                new Ticket(TicketType.PLAYER_SIMULATION, BLOCK_TICKING_LEVEL), pos);
        level.getChunkSource().addTicket(
                new Ticket(TicketType.PLAYER_LOADING, BLOCK_TICKING_LEVEL), pos);
    }

    private void removeBlockTickingTicket(long chunkKey) {
        final ChunkPos pos = new ChunkPos(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_SIMULATION, pos, 0);
        level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, pos, 0);
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
     * Level data that follows the host level's clock exactly, including modded
     * dimensions or a level whose data is not the shared overworld data. The
     * auxiliary level does not tick time itself ({@code tickTime=false}); this
     * wrapper is the single source of its {@code getGameTime()}.
     */
    private static final class HostClockLevelData extends DerivedLevelData {
        private final ServerLevel host;

        private HostClockLevelData(net.minecraft.world.level.storage.WorldData worldData,
                                   ServerLevelData fallback, ServerLevel host) {
            super(worldData, fallback);
            this.host = host;
        }

        @Override
        public long getGameTime() {
            return host.getGameTime();
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
            // The interacting client does not run vanilla's local sound
            // prediction (the structure click is consumed before prediction),
            // so do not exclude the acting player from the projected sound.
            if (mapped != null) {
                host.playSeededSound(null, mapped.x, mapped.y, mapped.z,
                        sound, source, volume, pitch, seed);
                return;
            }
            if (projecting()) {
                // Some vanilla blocks play at the player position, which is
                // already a host coordinate.
                host.playSeededSound(null, x, y, z, sound, source, volume, pitch, seed);
            }
            // Otherwise do not leave audio in the player-less void level.
        }

        @Override
        public void playSeededSound(@Nullable Entity except, Entity sourceEntity,
                                    Holder<SoundEvent> sound, SoundSource source,
                                    float volume, float pitch, long seed) {
            if (sourceEntity instanceof Player) {
                host.playSeededSound(null, sourceEntity, sound, source, volume, pitch, seed);
                return;
            }
            final Vec3 mapped = projectPoint(sourceEntity.getX(), sourceEntity.getY(),
                    sourceEntity.getZ());
            if (mapped != null) {
                host.playSeededSound(null, mapped.x, mapped.y, mapped.z,
                        sound, source, volume, pitch, seed);
                return;
            }
            if (projecting()) {
                host.playSeededSound(null, sourceEntity, sound, source, volume, pitch, seed);
            }
        }

        @Override
        public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position,
                              GameEvent.Context context) {
            // Keep the event available to listeners inside the copy (a sculk
            // sensor in a structure should still power structure redstone),
            // then project it into the host world at the structure pose.
            super.gameEvent(gameEvent, position, context);
            if (owner != null && (owner.boundaryBridge() == null
                    || !owner.boundaryBridge().guard()
                    .isGameEventToStructureDispatch())) {
                StructureGameEvents.forwardFromAuxiliary(owner, gameEvent, position, context);
            }
        }

        @Override
        public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
            if (type == LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK && owner != null) {
                if (owner.suppressingBreakFeedback()) {
                    // The redirect break path emits the host-side burst itself.
                    return;
                }
                if (owner.emitStructureBreakFeedback(pos, Block.stateById(data))) {
                    // The client-side 2001 handler works in BlockPos space and
                    // would duplicate this exact, pose-aware burst.
                    return;
                }
            }
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
                owner.totalBlockEventsQueued++;
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
        public BlockState getBlockState(BlockPos pos) {
            final LevelChunk chunk = getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            final BlockState local = chunk == null
                    ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
            if (!local.isAir() || owner == null || owner.isInsideRegion(pos)) {
                return local;
            }
            final BlockState boundary = owner.boundaryBlockState(pos);
            return boundary == null ? local : boundary;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            final LevelChunk chunk = getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            final BlockEntity local = chunk == null ? null : chunk.getBlockEntity(pos);
            if (local != null || owner == null || owner.isInsideRegion(pos)) {
                return local;
            }
            return owner.boundaryBlockEntity(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            final LevelChunk chunk = getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4);
            final BlockState local = chunk == null
                    ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
            final FluidState localFluid = local.getFluidState();
            if (!local.isAir() || owner == null || owner.isInsideRegion(pos)) {
                return localFluid;
            }
            final FluidState boundary = owner.boundaryFluidState(pos);
            return boundary == null ? localFluid : boundary;
        }

        @Override
        public int getSignal(BlockPos pos, Direction direction) {
            if (owner != null && owner.boundaryBridge() != null
                    && !owner.isInsideRegion(pos)) {
                return owner.boundarySignal(pos, direction);
            }
            final BlockState state = getBlockState(pos);
            int signal = state.getSignal(this, pos, direction);
            if (state.isRedstoneConductor(this, pos)) {
                signal = Math.max(signal, getDirectSignalTo(pos));
            }
            return signal;
        }

        @Override
        public int getDirectSignal(BlockPos pos, Direction direction) {
            if (owner != null && owner.boundaryBridge() != null
                    && !owner.isInsideRegion(pos)) {
                return owner.boundaryDirectSignal(pos, direction);
            }
            return getBlockState(pos).getDirectSignal(this, pos, direction);
        }

        @Override
        public void updateNeighborsAt(BlockPos pos, Block block, Orientation orientation) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.updateNeighborsAt(pos, block, orientation);
            }
            if (owner != null) {
                owner.onAuxiliaryNeighborUpdate(pos, block);
            }
        }

        @Override
        public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block,
                                                       net.minecraft.core.Direction direction,
                                                       Orientation orientation) {
            if (owner == null || owner.consumeNeighborBudget()) {
                super.updateNeighborsAtExceptFromFacing(pos, block, direction, orientation);
            }
            if (owner != null) {
                owner.onAuxiliaryNeighborUpdate(pos, block);
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
        /** Absolute positions that received a non-air copied state. */
        private final Set<Long> copiedPositions = new HashSet<>();
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
