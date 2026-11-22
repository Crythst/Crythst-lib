package io.mikaple.crythst.integration.minecraft.structure.view;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.api.sublevel.view.StructureLevelViews;
import io.mikaple.crythst.api.sublevel.view.StructureOperationTarget;
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.integration.minecraft.EditableStatefulVolume;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.collision.ServerStructureCollisionSource;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side {@link StructureLevelView} over the auxiliary void level.
 *
 * <p>The view maps structure-local voxels to the per-structure region of
 * {@link EmptyPhysicsLevel}. Reads delegate to
 * {@link LevelPhysics#liveBlock(PhysicsStructure, int, int, int)} (the numeric
 * equivalent of {@code voidLevel.getBlockState(auxiliaryPos)} with a
 * non-loading chunk check). Outside the copied volume, or when the auxiliary
 * chunk is unloaded, the result is air. The void level's region base offset is
 * never exposed.</p>
 *
 * <p>Writes go to the void block grid with vanilla semantics: the flags are
 * passed to {@code ServerLevel#setBlock}, so neighbour/redstone updates happen
 * inside the void dimension. The P0 edit-propagation pass
 * ({@link LevelPhysics#flushAuxiliaryBlockStates()}) then mirrors the new state
 * into the structure volume and side tables, reindexes the collider and
 * refreshes the auxiliary region. The previous structure-local state is
 * returned.</p>
 *
 * <p>When no auxiliary level exists (standalone worlds/tests), writes fall back
 * to the editable structure volume. When a void region exists but its chunk is
 * not loaded, a write returns {@code null} instead of loading a chunk.</p>
 *
 * <p>The view is stale after a split, removal or an out-of-band reindex.
 * {@link #isCurrent()} and {@link #epoch()} report that state; callers must
 * reacquire a view instead of reusing a stale one.</p>
 */
public final class ServerStructureLevelView implements StructureLevelView {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FALLBACK_SOLID = Blocks.STONE.defaultBlockState();
    private static final BlockPhysicsMapper.BlockProperties AIR_PROPERTIES =
            new BlockPhysicsMapper.BlockProperties(0.0, PhysicsMaterial.DEFAULT);

    /**
     * Non-loading live block lookup. Tests and alternate backends can supply
     * their own implementation; production uses {@link LevelPhysics#liveBlock}.
     */
    @FunctionalInterface
    public interface LiveBlockSource {
        /**
         * @return the live block at a structure-local voxel, or {@code null}
         *         when it is unloaded/not copied
         */
        @Nullable LiveBlock liveBlock(PhysicsStructure structure, int x, int y, int z);
    }

    /** Minimal live block data needed by the view. */
    public record LiveBlock(BlockState state, @Nullable BlockEntity blockEntity) {
        public LiveBlock {
            if (state == null) {
                throw new NullPointerException("state");
            }
        }
    }

    private final PhysicsStructure structure;
    private final @Nullable Level hostLevel;
    private final @Nullable LevelPhysics physics;
    private final @Nullable EmptyPhysicsLevel auxiliary;
    private final @Nullable LiveBlockSource liveSource;
    private final boolean liveAuthoritative;
    private Transform3d lastPose;
    private long epoch;
    private boolean stale;

    /**
     * Creates a production server view.
     *
     * @param structure authoritative structure
     * @param hostLevel host level, or {@code null} when only the structure is
     *                  needed (the physics level is used as fallback)
     * @param physics   owning physics world, or {@code null} for a standalone
     *                  volume-backed view that cannot refresh an auxiliary
     *                  region or remove a massless body
     */
    public ServerStructureLevelView(PhysicsStructure structure, @Nullable Level hostLevel,
                                    @Nullable LevelPhysics physics) {
        this(structure, hostLevel, physics,
                physics == null ? null : sourceFor(physics),
                physics != null && physics.subLevelActive(),
                physics == null ? null : physics.auxiliaryLevel());
    }

    ServerStructureLevelView(PhysicsStructure structure, @Nullable Level hostLevel,
                             @Nullable LevelPhysics physics,
                             @Nullable LiveBlockSource liveSource,
                             boolean liveAuthoritative) {
        this(structure, hostLevel, physics, liveSource, liveAuthoritative,
                physics == null ? null : physics.auxiliaryLevel());
    }

    ServerStructureLevelView(PhysicsStructure structure, @Nullable Level hostLevel,
                             @Nullable LevelPhysics physics,
                             @Nullable LiveBlockSource liveSource,
                             boolean liveAuthoritative,
                             @Nullable EmptyPhysicsLevel auxiliary) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        this.structure = structure;
        this.hostLevel = hostLevel != null ? hostLevel : (physics == null ? null : physics.level());
        this.physics = physics;
        this.auxiliary = auxiliary;
        this.liveSource = liveSource;
        this.liveAuthoritative = liveAuthoritative;
        this.lastPose = structure.closed() ? Transform3d.IDENTITY : structure.pose();
        this.epoch = StructureLevelViews.epoch(structure);
    }

    private static LiveBlockSource sourceFor(LevelPhysics physics) {
        return (structure, x, y, z) -> {
            final EmptyPhysicsLevel.LiveBlock live = physics.liveBlock(structure, x, y, z);
            return live == null ? null : new LiveBlock(live.state(), live.blockEntity());
        };
    }

    @Override
    public PhysicsStructure structure() {
        return structure;
    }

    @Override
    public @Nullable Level level() {
        return hostLevel;
    }

    @Override
    public double blockScale() {
        return structure.blockScale();
    }

    @Override
    public IntBounds3 bounds() {
        return structure.volume().bounds();
    }

    @Override
    public Transform3d pose() {
        if (!structure.closed()) {
            lastPose = structure.pose();
        }
        return lastPose;
    }

    @Override
    public boolean isWritable() {
        return !isClientSide() && !stale && !structure.closed()
                && (auxiliary != null || editable(structure.volume()) != null);
    }

    @Override
    public long epoch() {
        return epoch;
    }

    @Override
    public boolean isCurrent() {
        return !stale && !structure.closed()
                && epoch == StructureLevelViews.epoch(structure);
    }

    @Override
    public BlockState getBlockState(BlockPos localPos) {
        if (localPos == null || structure.closed()) {
            return AIR;
        }
        final IntBounds3 bounds = bounds();
        final int x = localPos.getX();
        final int y = localPos.getY();
        final int z = localPos.getZ();
        if (!bounds.contains(x, y, z)) {
            return AIR;
        }
        if (liveAuthoritative && liveSource != null) {
            final LiveBlock live = liveSource.liveBlock(structure, x, y, z);
            // An unloaded auxiliary chunk is deliberately indistinguishable
            // from air. The view must never load a chunk on a query path.
            return live == null ? AIR : live.state();
        }
        return storedState(x, y, z);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos localPos) {
        if (localPos == null || structure.closed()) {
            return null;
        }
        final IntBounds3 bounds = bounds();
        if (!bounds.contains(localPos.getX(), localPos.getY(), localPos.getZ())) {
            return null;
        }
        if (!liveAuthoritative || liveSource == null) {
            return null;
        }
        final LiveBlock live = liveSource.liveBlock(structure, localPos.getX(),
                localPos.getY(), localPos.getZ());
        return live == null ? null : live.blockEntity();
    }

    @Override
    public @Nullable LevelChunk getChunkIfLoaded(int chunkX, int chunkZ) {
        if (stale || structure.closed() || auxiliary == null) {
            return null;
        }
        final EmptyPhysicsLevel.RegionView region = auxiliary.regionView(structure);
        if (region == null) {
            return null;
        }
        final IntBounds3 bounds = bounds();
        final int localX = chunkX * 16 + 8;
        final int localZ = chunkZ * 16 + 8;
        if (localX < bounds.minX() || localX >= bounds.maxX()
                || localZ < bounds.minZ() || localZ >= bounds.maxZ()) {
            return null;
        }
        final int localY = bounds.minY();
        final BlockPos auxiliaryPos = region.toAuxiliaryVoxel(localX, localY, localZ);
        if (auxiliaryPos == null) {
            return null;
        }
        return auxiliary.level().getChunkSource().getChunkNow(
                auxiliaryPos.getX() >> 4, auxiliaryPos.getZ() >> 4);
    }

    private BlockState storedState(int x, int y, int z) {
        final VoxelVolume volume = structure.volume();
        if (!volume.bounds().contains(x, y, z)) {
            return AIR;
        }
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState stored = stateful.blockState(x, y, z);
            if (stored != null) {
                return stored;
            }
        }
        final BlockState sideTable = StructureBlockStates.get(structure, x, y, z);
        if (sideTable != null) {
            return sideTable;
        }
        // Match the auxiliary copy's fallback for a solid voxel without a
        // stored block state (for example a demo volume filled with stone).
        return volume.isSolid(x, y, z) ? FALLBACK_SOLID : AIR;
    }

    @Override
    public RegistryAccess registryAccess() {
        final Level host = level();
        return host == null ? RegistryAccess.EMPTY : host.registryAccess();
    }

    @Override
    public @Nullable StructureOperationTarget operationTarget(BlockPos localPos) {
        return operationTarget(localPos, null, Direction.UP);
    }

    @Override
    public @Nullable StructureOperationTarget operationTarget(BlockPos localPos,
                                                              Vec3 localHitLocation,
                                                              Direction direction) {
        if (localPos == null || structure.closed() || stale) {
            return null;
        }
        final IntBounds3 bounds = bounds();
        if (!bounds.contains(localPos.getX(), localPos.getY(), localPos.getZ())) {
            return null;
        }
        final EmptyPhysicsLevel aux = auxiliary;
        if (aux == null) {
            return null;
        }
        final EmptyPhysicsLevel.RegionView region = aux.regionView(structure);
        if (region == null) {
            return null;
        }
        final BlockPos auxiliaryPos = region.toAuxiliaryVoxel(localPos.getX(),
                localPos.getY(), localPos.getZ());
        if (auxiliaryPos == null) {
            return null;
        }
        final ServerLevel voidLevel = aux.level();
        final LevelChunk chunk = voidLevel.getChunkSource().getChunkNow(
                auxiliaryPos.getX() >> 4, auxiliaryPos.getZ() >> 4);
        if (chunk == null) {
            // A vanilla operation would load the chunk. Refuse instead.
            return null;
        }
        BlockHitResult hit = null;
        if (localHitLocation != null) {
            final double localX = clampFraction(localHitLocation.x - localPos.getX());
            final double localY = clampFraction(localHitLocation.y - localPos.getY());
            final double localZ = clampFraction(localHitLocation.z - localPos.getZ());
            hit = new BlockHitResult(new Vec3(
                    auxiliaryPos.getX() + localX,
                    auxiliaryPos.getY() + localY,
                    auxiliaryPos.getZ() + localZ),
                    direction == null ? Direction.UP : direction,
                    auxiliaryPos, false);
        }
        return new StructureOperationTarget(this, voidLevel, auxiliaryPos,
                localPos, hit);
    }

    @Override
    public @Nullable BlockState setBlock(BlockPos localPos, BlockState state, int flags, int recursion) {
        if (localPos == null || state == null) {
            throw new NullPointerException("localPos/state");
        }
        if (this.stale || structure.closed() || isClientSide()) {
            return null;
        }
        final IntBounds3 bounds = bounds();
        if (!bounds.contains(localPos.getX(), localPos.getY(), localPos.getZ())) {
            return null;
        }
        if (auxiliary != null) {
            return setVoidBlock(localPos, state, flags, recursion);
        }
        return setVolumeBlock(localPos, state);
    }

    /**
     * Vanilla write path: set the block in the auxiliary void grid, including
     * its neighbour updates, then let the P0 propagation pass mirror it back.
     */
    private @Nullable BlockState setVoidBlock(BlockPos localPos, BlockState state,
                                              int flags, int recursion) {
        final EmptyPhysicsLevel aux = auxiliary;
        if (aux == null) {
            return null;
        }
        final EmptyPhysicsLevel.RegionView region = aux.regionView(structure);
        if (region == null) {
            return null;
        }
        final BlockPos auxiliaryPos = region.toAuxiliaryVoxel(localPos.getX(),
                localPos.getY(), localPos.getZ());
        if (auxiliaryPos == null) {
            return null;
        }
        final ServerLevel voidLevel = aux.level();
        final LevelChunk chunk = voidLevel.getChunkSource().getChunkNow(
                auxiliaryPos.getX() >> 4, auxiliaryPos.getZ() >> 4);
        if (chunk == null) {
            // Writing would force a synchronous chunk creation. The region is
            // only active while its chunks are loaded; refuse otherwise.
            return null;
        }
        final BlockState previous = chunk.getBlockState(auxiliaryPos);
        if (previous.equals(state)) {
            return previous;
        }
        final boolean changed;
        try {
            changed = voidLevel.setBlock(auxiliaryPos, state, flags, recursion);
        } catch (RuntimeException failure) {
            Crythst.LOGGER.warn("Could not write structure voxel {} into the auxiliary level",
                    auxiliaryPos, failure);
            return null;
        }
        if (!changed) {
            return previous;
        }
        // P0 propagation: mirror void-level state(s) into the structure
        // volume/side tables, reindex, refresh the auxiliary copy and split if
        // the remaining cells became disconnected.
        if (physics != null) {
            try {
                physics.flushAuxiliaryBlockStates();
            } catch (RuntimeException failure) {
                Crythst.LOGGER.warn("Could not propagate auxiliary edit for structure body #{}",
                        structure.body().id(), failure);
            }
        }
        stale = structure.closed();
        invalidateCaches();
        epoch = StructureLevelViews.advanceEpoch(structure);
        return previous;
    }

    /**
     * Fallback for worlds without an auxiliary void level: write through the
     * editable volume and reindex directly. Kept for standalone tests and
     * deployments that deliberately disable the void level.
     */
    private @Nullable BlockState setVolumeBlock(BlockPos localPos, BlockState state) {
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        if (!bounds.contains(localPos.getX(), localPos.getY(), localPos.getZ())) {
            return null;
        }
        final Editable editable = editable(volume);
        if (editable == null) {
            return null;
        }

        final BlockState previous = getBlockState(localPos);
        final int x = localPos.getX();
        final int y = localPos.getY();
        final int z = localPos.getZ();
        final boolean previousSolid = volume.isSolid(x, y, z);
        final boolean nextSolid = !state.isAir();
        if (previous.equals(state) && previousSolid == nextSolid) {
            return previous;
        }

        final double previousMass = previousSolid ? volume.blockMass(x, y, z) : 0.0;
        final PhysicsMaterial previousMaterial = previousSolid
                ? volume.blockMaterial(x, y, z) : PhysicsMaterial.DEFAULT;
        final BlockState previousVolumeState = volume instanceof StatefulVoxelVolume stateful
                ? stateful.blockState(x, y, z) : null;
        final BlockState previousSideState = StructureBlockStates.get(structure, x, y, z);

        final BlockPhysicsMapper.BlockProperties properties = nextSolid
                ? blockProperties(state) : AIR_PROPERTIES;
        final double mass = properties.mass() > 0.0
                ? properties.mass() : defaultBlockMass();

        editable.set(x, y, z, nextSolid, mass, properties.material(), state);
        if (nextSolid) {
            StructureBlockStates.set(structure, x, y, z, state);
        } else {
            StructureBlockStates.remove(structure, x, y, z);
        }

        try {
            if (StructureSplitter.isMassless(volume)) {
                if (physics == null) {
                    rollback(editable, x, y, z, previousSolid, previousMass, previousMaterial,
                            previousVolumeState, previousSideState);
                    return null;
                }
                StructureSplitter.removeIfMassless(physics, structure, volume);
                stale = structure.closed();
                invalidateCaches();
                epoch = StructureLevelViews.advanceEpoch(structure);
                return previous;
            }
            structure.reindex(volume);
        } catch (RuntimeException failure) {
            rollback(editable, x, y, z, previousSolid, previousMass, previousMaterial,
                    previousVolumeState, previousSideState);
            return null;
        }

        if (physics != null) {
            try {
                physics.refreshStructure(structure, volume);
            } catch (RuntimeException failure) {
                Crythst.LOGGER.warn("Could not refresh structure body #{} after a level-view write",
                        structure.body().id(), failure);
            }
        }
        stale = structure.closed();
        invalidateCaches();
        epoch = StructureLevelViews.advanceEpoch(structure);
        return previous;
    }

    private static double clampFraction(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static BlockPhysicsMapper.BlockProperties blockProperties(BlockState state) {
        try {
            return MinecraftPhysics.blockPhysics().properties(state);
        } catch (RuntimeException failure) {
            return new BlockPhysicsMapper.BlockProperties(defaultBlockMass(), PhysicsMaterial.DEFAULT);
        }
    }

    private static double defaultBlockMass() {
        final double configured = MinecraftPhysics.config().defaultBlockMass;
        return configured > 0.0 ? configured : 1.0;
    }

    private void rollback(Editable editable, int x, int y, int z, boolean solid,
                          double mass, PhysicsMaterial material, @Nullable BlockState volumeState,
                          @Nullable BlockState sideState) {
        editable.set(x, y, z, solid, mass, material, volumeState);
        if (sideState == null) {
            StructureBlockStates.remove(structure, x, y, z);
        } else {
            StructureBlockStates.set(structure, x, y, z, sideState);
        }
    }

    private void invalidateCaches() {
        structure.invalidateBlockEntityActors();
        if (physics != null) {
            ServerStructureCollisionSource.invalidate(physics);
            physics.invalidateTerrain();
        }
    }

    /**
     * Editable-volume bridge for the volume types the interaction layer can
     * already grow or mutate, including split components. Read-only volumes
     * deliberately return {@code null}.
     */
    private interface Editable {
        void set(int x, int y, int z, boolean solid, double mass,
                 PhysicsMaterial material, @Nullable BlockState state);
    }

    static @Nullable Editable editable(VoxelVolume volume) {
        if (volume instanceof EditableStatefulVolume stateful) {
            return stateful::setBlock;
        }
        if (volume instanceof RestoredStructureVolume restored) {
            return restored::setBlock;
        }
        if (volume instanceof MutableVoxelVolume mutable) {
            return (x, y, z, solid, mass, material, state) -> {
                if (solid) {
                    mutable.setBlock(x, y, z, true, mass, material);
                } else {
                    mutable.setSolid(x, y, z, false);
                }
            };
        }
        return null;
    }
}
