package io.mikaple.crythst.integration.minecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Structure-aware {@link BlockGetter}/{@link CollisionGetter} used by
 * navigation helpers that are not already inside a
 * {@code PathNavigationRegion}.
 *
 * <p>Reads prefer the real world block/fluid. A structure state is returned
 * only when the world cell is air / has no fluid. World reads are made through
 * {@code ChunkSource#getChunkNow}, so a probe can never synchronously load a
 * chunk: an unloaded world cell is treated as air and the structure (if any)
 * still answers.</p>
 *
 * <p>The probe is immutable apart from the index's own cache invalidation. It
 * implements {@link CollisionGetter} so vanilla shape resolution (fences,
 * walls, stairs, slabs, ...) sees structure neighbours through the same
 * getter.</p>
 */
public final class StructurePathProbe implements CollisionGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final @Nullable Level level;
    private final @Nullable BlockGetter world;
    private final StructurePathIndex index;
    private final @Nullable WorldBorder border;
    private final int minY;
    private final int height;

    private StructurePathProbe(@Nullable Level level, @Nullable BlockGetter world,
                               StructurePathIndex index, @Nullable WorldBorder border,
                               int minY, int height) {
        if (index == null) {
            throw new NullPointerException("index");
        }
        this.level = level;
        this.world = world;
        this.index = index;
        this.border = border;
        this.minY = minY;
        this.height = Math.max(0, height);
    }

    /** Probe over a real level, using non-loading chunk reads. */
    public static StructurePathProbe of(Level level, StructurePathIndex index) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        return new StructurePathProbe(level, null, index, level.getWorldBorder(),
                level.getMinY(), level.getHeight());
    }

    /** Probe over an already-captured read-only getter. Mainly for tests. */
    public static StructurePathProbe of(BlockGetter world, StructurePathIndex index,
                                        @Nullable WorldBorder border, int minY, int height) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        return new StructurePathProbe(null, world, index, border, minY, height);
    }

    public StructurePathIndex index() {
        return index;
    }

    public boolean hasStructures() {
        return index.hasStructures();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        final BlockState worldState = worldState(pos);
        if (worldState != null && !worldState.isAir()) {
            return worldState;
        }
        final BlockState structure = index.structureState(pos);
        if (structure != null && !structure.isAir()) {
            return structure;
        }
        return worldState == null ? AIR : worldState;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        final FluidState worldFluid = worldFluid(pos);
        if (worldFluid != null && !worldFluid.isEmpty()) {
            return worldFluid;
        }
        final FluidState structure = index.structureFluid(pos);
        if (structure != null && !structure.isEmpty()) {
            return structure;
        }
        return worldFluid == null ? Fluids.EMPTY.defaultFluidState() : worldFluid;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        if (level != null) {
            final LevelChunk chunk = loadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(pos);
        }
        return world == null ? null : world.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public @Nullable WorldBorder getWorldBorder() {
        return border;
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        // Collision traversal must use this hybrid view, not the raw world
        // chunk, otherwise structure blocks are invisible to noCollision().
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        return List.of();
    }

    @Override
    public boolean noBorderCollision(@Nullable Entity entity, AABB box) {
        // Test probes may not have a level; production always supplies one.
        return border == null || CollisionGetter.super.noBorderCollision(entity, box);
    }

    private @Nullable BlockState worldState(BlockPos pos) {
        if (level != null) {
            if (pos.getY() < minY || pos.getY() >= minY + height) {
                return AIR;
            }
            final LevelChunk chunk = loadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? AIR : chunk.getBlockState(pos);
        }
        return world == null ? AIR : world.getBlockState(pos);
    }

    private @Nullable FluidState worldFluid(BlockPos pos) {
        if (level != null) {
            if (pos.getY() < minY || pos.getY() >= minY + height) {
                return null;
            }
            final LevelChunk chunk = loadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getFluidState(pos);
        }
        return world == null ? null : world.getFluidState(pos);
    }

    private @Nullable LevelChunk loadedChunk(int chunkX, int chunkZ) {
        if (level == null) {
            return null;
        }
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }
}
