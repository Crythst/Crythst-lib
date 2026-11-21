package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.mixin.MinecraftServerAccessor;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private final ServerLevel level;
    private final Map<Long, Integer> activeChunks = new HashMap<>();
    private final Map<Long, Region> regions = new HashMap<>();
    private int nextRegionX;
    private boolean closed;

    private EmptyPhysicsLevel(ServerLevel level) {
        this.level = level;
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
            final ServerLevel voidLevel = new ServerLevel(server, accessor.crythst$executor(),
                    accessor.crythst$storageSource(), data, key, stem, false, host.getSeed(), List.of(), false);
            voidLevel.noSave = true;
            return new EmptyPhysicsLevel(voidLevel);
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
        final Region region = new Region(regionKey, baseX, baseY, baseZ,
                bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        regions.put(regionKey, region);
        // Indices are monotonic and never reused: removing a region must not
        // let a later structure overlap the old region's chunks. Reserve the
        // slot before copying so a partially copied region can be removed
        // safely if block placement fails.
        nextRegionX = Math.addExact(nextRegionX, bounds.sizeX() + REGION_MARGIN);

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
                    // Flag 2 skips neighbour updates: the copied region is an
                    // isolated snapshot, not a live world edit.
                    if (!level.setBlock(pos, state, 2)) {
                        continue;
                    }
                    final long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
                    if (region.chunks.add(chunkKey)) {
                        activeChunks.merge(chunkKey, 1, Integer::sum);
                        addBlockTickingTicket(chunkKey);
                    }
                }
            }
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
                }
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Could not restore block entity at {} in auxiliary level", pos, t);
            }
        }
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
     * Ticks only chunks that contain copied blocks. No chunk is loaded or ticked
     * just because the void level exists.
     */
    public void tick(int randomTickSpeed) {
        if (closed || activeChunks.isEmpty()) {
            return;
        }
        for (long chunkKey : activeChunks.keySet()) {
            final int chunkX = ChunkPos.getX(chunkKey);
            final int chunkZ = ChunkPos.getZ(chunkKey);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk != null && !chunk.isEmpty()) {
                level.tickChunk(chunk, randomTickSpeed);
            }
        }
        // All other chunks are empty, so this only sees tickers from active
        // structures (and removed structures clear their blocks).
        level.tickBlockEntities();
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
        try {
            region.clearBlocks(level);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Error clearing auxiliary void region for body #{}",
                    region.structureId, t);
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
        try {
            level.close();
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Error closing auxiliary void level", t);
        }
    }

    private static final class Region {
        private final long structureId;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final Set<Long> chunks = new HashSet<>();

        private Region(long structureId, int baseX, int baseY, int baseZ,
                       int sizeX, int sizeY, int sizeZ) {
            this.structureId = structureId;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
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
