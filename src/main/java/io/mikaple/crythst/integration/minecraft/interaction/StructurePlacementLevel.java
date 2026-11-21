package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;

/**
 * A {@link LevelReader} that presents a structure's local voxel grid to vanilla
 * block-placement rules.
 *
 * <p>Queries use structure-local voxel coordinates. Inside the volume they are
 * answered from the structure's block states; outside they are mapped back to
 * the real world through the structure pose and block scale. This is important
 * for support checks: a torch placed on a structure face must see the structure
 * voxel below it as its support, not the (usually air) real-world block at the
 * same coordinates. The outside delegation still lets a placement next to a
 * real wall or floor see that real neighbour.</p>
 *
 * <p>The class deliberately implements only {@link LevelReader}. Placement
 * state resolution ({@code Block#getStateForPlacement}) still receives the real
 * {@code Level} through {@link net.minecraft.world.item.context.BlockPlaceContext};
 * validation and {@link BlockState#canSurvive(LevelReader, BlockPos)} use this
 * view. See {@code docs/player-structure-interaction.md} for that limitation.</p>
 */
final class StructurePlacementLevel implements LevelReader {
    @FunctionalInterface
    interface StateSource {
        /**
         * @return the structure state at the local voxel, or {@code null}
         *         when the voxel has no stored state
         */
        @Nullable
        BlockState stateAt(int x, int y, int z);
    }

    private final LevelReader delegate;
    private final Transform3d pose;
    private final double blockScale;
    private final IntBounds3 bounds;
    private final StateSource states;
    private final int minY;

    StructurePlacementLevel(@Nullable LevelReader delegate, Transform3d pose,
                            double blockScale, IntBounds3 bounds, StateSource states) {
        this.delegate = delegate;
        this.pose = pose;
        this.blockScale = positiveScale(blockScale);
        this.bounds = bounds;
        this.states = states;
        final int delegateMin = delegate == null ? Integer.MAX_VALUE : delegate.getMinY();
        this.minY = Math.min(bounds.minY(), delegateMin);
    }

    private static double positiveScale(double value) {
        return value > 0.0 && Double.isFinite(value) ? value : 1.0;
    }

    private boolean inside(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    private BlockState structureState(int x, int y, int z) {
        final BlockState state = states.stateAt(x, y, z);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    /**
     * Maps a structure-local voxel to the real world position of its centre.
     * Used only for positions outside the structure volume.
     */
    private BlockPos worldPos(int x, int y, int z) {
        final double scale = blockScale;
        final Vector3d local = new Vector3d(
                (x + 0.5) * scale,
                (y + 0.5) * scale,
                (z + 0.5) * scale);
        final Vector3d world = pose == null ? local : pose.transformPoint(local);
        return BlockPos.containing(world.x, world.y, world.z);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (inside(pos.getX(), pos.getY(), pos.getZ())) {
            return structureState(pos.getX(), pos.getY(), pos.getZ());
        }
        if (delegate == null) {
            return Blocks.AIR.defaultBlockState();
        }
        final BlockPos world = worldPos(pos.getX(), pos.getY(), pos.getZ());
        return delegate.getBlockState(world);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (inside(pos.getX(), pos.getY(), pos.getZ())) {
            return structureState(pos.getX(), pos.getY(), pos.getZ()).getFluidState();
        }
        if (delegate == null) {
            return Fluids.EMPTY.defaultFluidState();
        }
        final BlockPos world = worldPos(pos.getX(), pos.getY(), pos.getZ());
        return delegate.getFluidState(world);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        if (inside(pos.getX(), pos.getY(), pos.getZ()) || delegate == null) {
            return null;
        }
        return delegate.getBlockEntity(worldPos(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    public int getHeight() {
        final int maxY = delegate == null
                ? bounds.maxY()
                : Math.max(bounds.maxY(), delegate.getMaxY());
        return Math.max(1, maxY - minY);
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return delegate == null ? null : delegate.getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return delegate == null ? null : delegate.getWorldBorder();
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable net.minecraft.world.entity.Entity entity,
                                                AABB box) {
        // Placement obstruction is checked explicitly against the structure
        // pose in StructurePlacementValidation; the virtual level itself should
        // never pull real-world entity shapes into vanilla shape rules.
        return List.of();
    }

    @Override
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean load) {
        return delegate == null
                ? null
                : delegate.getChunk(chunkX, chunkZ, status, load);
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        // The structure grid is a virtual, always-loaded view. Local chunk
        // coordinates are not world coordinates, so delegating this check
        // would reject valid virtual positions for structures far from spawn.
        return true;
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        if (x >= bounds.minX() && x < bounds.maxX()
                && z >= bounds.minZ() && z < bounds.maxZ()) {
            return bounds.maxY();
        }
        return delegate == null ? getMaxY() : delegate.getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return delegate == null ? 0 : delegate.getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return delegate == null ? null : delegate.getBiomeManager();
    }

    @Override
    public @Nullable Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return delegate == null ? null : delegate.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public boolean isClientSide() {
        return delegate != null && delegate.isClientSide();
    }

    @Override
    public int getSeaLevel() {
        return delegate == null ? 0 : delegate.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return delegate == null ? null : delegate.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return delegate == null ? null : delegate.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return delegate == null ? FeatureFlagSet.of() : delegate.enabledFeatures();
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return delegate == null ? null : delegate.environmentAttributes();
    }
}
