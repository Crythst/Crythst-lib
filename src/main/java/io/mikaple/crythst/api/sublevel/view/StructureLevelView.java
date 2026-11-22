package io.mikaple.crythst.api.sublevel.view;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A level-like view over the blocks of one physics structure.
 *
 * <p>All {@link BlockPos} arguments and results are <em>structure-local voxel
 * coordinates</em>: the integer grid returned by
 * {@link PhysicsStructure#volume()}. They are never auxiliary void
 * level coordinates. The auxiliary level's region remap is an implementation
 * detail of {@link #getBlockState(BlockPos)} and is not observable through this
 * interface.</p>
 *
 * <p>This is intentionally a small, stable interface instead of a
 * {@code Level} subclass. It covers block/fluid/block-entity queries, block
 * writes for authoritative server views, exact collision shapes and world-space
 * projection. A full {@code Level} facade (chunks, lighting, entities, tick
 * lists) is a later step; see {@code docs/structure-level-view-design.md}.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A view is a snapshot of "this structure object". A write, reindex, split or
 * removal may replace the structure body or its volume; the old view is then
 * stale. Callers should reacquire a view via {@link StructureLevelViews} or
 * {@link io.mikaple.crythst.api.sublevel.StructureContainer#view(long)} after
 * observing a change. {@link #epoch()} / {@link #isCurrent()} let callers
 * detect that a view was superseded without retaining a reference to the
 * structure forever.</p>
 *
 * <h2>Coordinates</h2>
 *
 * <ul>
 *   <li>voxel {@code (x, y, z)} occupies local metres
 *       {@code [x, x + 1) * blockScale}.</li>
 *   <li>the centre of that voxel is local metres
 *       {@code ((x + 0.5) * blockScale, ...)}.</li>
 *   <li>{@link #pose()} maps local metres to world space.</li>
 * </ul>
 */
public interface StructureLevelView {
    /** The live structure this view reads, or {@code null} for a network snapshot. */
    @Nullable PhysicsStructure structure();

    /** Host dimension the structure lives in, or {@code null} when unavailable. */
    @Nullable Level level();

    /** Whether this view belongs to a client level. */
    default boolean isClientSide() {
        final Level host = level();
        return host != null && host.isClientSide();
    }

    /** Physics body id, or the snapshot id for a network-backed client view. */
    default long bodyId() {
        final PhysicsStructure structure = structure();
        return structure == null ? -1L : structure.body().id();
    }

    /** Metres per voxel. */
    double blockScale();

    /** Integer voxel bounds of the whole structure. */
    IntBounds3 bounds();

    /** Structure pose mapping local metres to world space. */
    Transform3d pose();

    /** Lowest writable/readable local Y (inclusive). */
    default int minBuildHeight() {
        return bounds().minY();
    }

    /** Highest local Y (exclusive, matching vanilla build-height semantics). */
    default int maxBuildHeight() {
        return bounds().maxY();
    }

    /** Vanilla-style alias for {@link #minBuildHeight()}. */
    default int getMinBuildHeight() {
        return minBuildHeight();
    }

    /** Vanilla-style alias for {@link #maxBuildHeight()}. */
    default int getMaxBuildHeight() {
        return maxBuildHeight();
    }

    /** Number of local Y levels in this view. */
    default int height() {
        return Math.max(0, maxBuildHeight() - minBuildHeight());
    }

    /** Whether {@link #setBlock} and {@link #removeBlock} can change this view. */
    default boolean isWritable() {
        return false;
    }

    /**
     * Monotonic revision of the owning structure at the time this view was
     * acquired. Server views update it after their own successful writes so a
     * view stays current for its writer; every other view of the same
     * structure becomes stale.
     */
    default long epoch() {
        return 0L;
    }

    /**
     * Whether this view still describes the current structure revision. A
     * network snapshot is immutable and reports {@code true} until the caller
     * replaces it.
     */
    default boolean isCurrent() {
        final PhysicsStructure structure = structure();
        return structure == null || !structure.closed();
    }

    // ------------------------------------------------------------------
    // Block / fluid / block-entity access.
    // ------------------------------------------------------------------

    /**
     * Block state at a structure-local voxel.
     *
     * <p>Outside {@link #bounds()} this always returns air. Server views return
     * air for a copied block whose auxiliary chunk is currently unloaded; they
     * never load a chunk synchronously.</p>
     */
    BlockState getBlockState(BlockPos localPos);

    /** Fluid state implied by the block state at a local voxel. */
    default FluidState getFluidState(BlockPos localPos) {
        return getBlockState(localPos).getFluidState();
    }

    /**
     * Live block entity at a local voxel, or {@code null}.
     *
     * <p>Server views read the block entity from the auxiliary ticking level,
     * so its {@link BlockEntity#getBlockPos()} is an internal auxiliary
     * position. Callers must not persist or expose that position; use this view
     * for structure-local logic only.</p>
     */
    default @Nullable BlockEntity getBlockEntity(BlockPos localPos) {
        return null;
    }

    /** Whether the local voxel is outside the bounds or contains air. */
    default boolean isEmptyBlock(BlockPos localPos) {
        return getBlockState(localPos).isAir();
    }

    /**
     * All block states in a local AABB. The box is in local voxel coordinates,
     * not world coordinates.
     */
    default Stream<BlockState> getBlockStates(AABB localBox) {
        if (localBox == null) {
            return Stream.empty();
        }
        return BlockPos.betweenClosedStream(localBox).map(this::getBlockState);
    }

    /** Registries used by this view; never {@code null}. */
    RegistryAccess registryAccess();

    // ------------------------------------------------------------------
    // Level metadata, entities, chunks and random source.
    // ------------------------------------------------------------------

    /**
     * Dimension key of the host level, or {@code null} when the view has no
     * host level.
     */
    default @Nullable ResourceKey<Level> dimension() {
        final Level host = level();
        return host == null ? null : host.dimension();
    }

    /** Identifier of {@link #dimension()}, or {@code null}. */
    default @Nullable Identifier dimensionId() {
        final ResourceKey<Level> dimension = dimension();
        return dimension == null ? null : dimension.identifier();
    }

    /** Game time of the host level, or {@code 0} for a view without a host. */
    default long gameTime() {
        final Level host = level();
        return host == null ? 0L : host.getGameTime();
    }

    /** Day time of the host level, or {@code 0} for a view without a host. */
    default long dayTime() {
        final Level host = level();
        return host == null ? 0L : host.getOverworldClockTime();
    }

    /**
     * Random source of the host level.
     *
     * <p>A view without a host returns a fresh unseeded source; block logic on a
     * real structure always has a host level.</p>
     */
    default RandomSource random() {
        final Level host = level();
        return host == null ? RandomSource.create() : host.getRandom();
    }

    /**
     * Spawns a host-world entity. The entity must already have a world-space
     * position and, on a server, an appropriate entity type.
     *
     * <p>Crythst structures have no entity container; entities live in the host
     * world. This method is therefore a host-level spawn, not a structure-local
     * insertion.</p>
     *
     * @return {@code true} when the entity was added
     */
    default boolean addFreshEntity(Entity entity) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        return level() instanceof ServerLevel server && server.addFreshEntity(entity);
    }

    /**
     * Moves an entity to a structure-local metre position, then spawns it in
     * the host world.
     *
     * <p>No structure velocity or rotation is applied to the entity; callers
     * that want a moving entity to inherit the structure frame should set its
     * velocity explicitly.</p>
     *
     * @return {@code true} when the entity was added
     */
    default boolean addFreshEntityAtLocal(Entity entity, Vector3dc localMetres) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        if (localMetres == null) {
            throw new NullPointerException("localMetres");
        }
        final Vector3d world = localToWorld(localMetres, new Vector3d());
        entity.snapTo(world.x, world.y, world.z, entity.getYRot(), entity.getXRot());
        return addFreshEntity(entity);
    }

    /**
     * Host-world entities whose bounding boxes overlap the projection of a
     * local voxel box.
     *
     * <p>The projection uses the world AABB of the eight transformed corners;
     * the result is therefore a coarse superset for a rotated structure, not an
     * exact OBB test. Entities are ordinary host entities.</p>
     *
     * @param localVoxelBox local voxel coordinates (not metres)
     * @param filter        optional predicate, or {@code null}
     */
    default List<Entity> getEntitiesInLocalBox(AABB localVoxelBox,
                                               @Nullable java.util.function.Predicate<? super Entity> filter) {
        final Level host = level();
        if (host == null || localVoxelBox == null) {
            return List.of();
        }
        final AABB worldBox = localVoxelBoxToWorldAabb(localVoxelBox);
        if (filter == null) {
            return List.copyOf(host.getEntitiesOfClass(Entity.class, worldBox));
        }
        return List.copyOf(host.getEntitiesOfClass(Entity.class, worldBox, filter));
    }

    /**
     * World AABB enclosing the projection of a local voxel box.
     */
    default AABB localVoxelBoxToWorldAabb(AABB localVoxelBox) {
        if (localVoxelBox == null) {
            throw new NullPointerException("localVoxelBox");
        }
        final double scale = blockScale();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final double x = ((i & 1) == 0 ? localVoxelBox.minX : localVoxelBox.maxX) * scale;
            final double y = ((i & 2) == 0 ? localVoxelBox.minY : localVoxelBox.maxY) * scale;
            final double z = ((i & 4) == 0 ? localVoxelBox.minZ : localVoxelBox.maxZ) * scale;
            final Vector3d world = localToWorld(new Vector3d(x, y, z), new Vector3d());
            minX = Math.min(minX, world.x);
            minY = Math.min(minY, world.y);
            minZ = Math.min(minZ, world.z);
            maxX = Math.max(maxX, world.x);
            maxY = Math.max(maxY, world.y);
            maxZ = Math.max(maxZ, world.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Loaded chunk for a local chunk coordinate, without loading one.
     *
     * <p>The default implementation projects the local chunk centre into the
     * host world. Server views override this to read the auxiliary ticking
     * region; client views return {@code null} because they have no writable
     * chunk storage.</p>
     */
    default @Nullable LevelChunk getChunkIfLoaded(int chunkX, int chunkZ) {
        final Level host = level();
        if (host == null || isClientSide()) {
            return null;
        }
        final BlockPos local = new BlockPos(chunkX * 16 + 8, minBuildHeight(), chunkZ * 16 + 8);
        final BlockPos world = localBlockToWorld(local);
        return host.getChunkSource().getChunkNow(world.getX() >> 4, world.getZ() >> 4);
    }

    /** Whether a local chunk is currently loaded; never loads one. */
    default boolean isChunkLoaded(int chunkX, int chunkZ) {
        return getChunkIfLoaded(chunkX, chunkZ) != null;
    }

    /**
     * Whether an explicit
     * {@link io.mikaple.crythst.api.sublevel.ticket.StructureLoadingTickets.StructureLoadingTicket}
     * currently pins the host chunks of this structure.
     */
    default boolean isKeptLoaded() {
        final PhysicsStructure structure = structure();
        if (structure == null || !(level() instanceof ServerLevel server)) {
            return false;
        }
        return !io.mikaple.crythst.api.sublevel.ticket.StructureLoadingTickets
                .tickets(server, bodyId()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Block writes (authoritative server views only).
    // ------------------------------------------------------------------

    /**
     * Replaces one local voxel.
     *
     * <p>Server views write through the editable structure volume (or its block
     * state side table), reindex the collider, refresh the auxiliary level,
     * invalidate collision/actor caches and advance {@link #epoch()}.
     * Positions outside {@link #bounds()} are rejected: growing a volume is the
     * interaction/assembly layer's job, not a block write.</p>
     *
     * <p>{@code flags} and {@code recursion} are accepted for call-site
     * compatibility with vanilla {@code Level#setBlock}. The current
     * implementation refreshes the whole auxiliary region after every accepted
     * edit, so neighbour-update flags do not change the result. Cross-boundary
     * neighbour updates are deferred (see the design doc).</p>
     *
     * @return the previous structure-local block state, or {@code null} when
     *         this view is read-only, the position is outside the volume, the
     *         backing volume cannot be edited, or the edit was rolled back
     */
    default @Nullable BlockState setBlock(BlockPos localPos, BlockState state, int flags, int recursion) {
        return null;
    }

    /**
     * {@link #setBlock(BlockPos, BlockState, int, int)} with vanilla's standard
     * update flags ({@code 3}, no recursion limit).
     */
    default @Nullable BlockState setBlockAndUpdate(BlockPos localPos, BlockState state) {
        return setBlock(localPos, state, 3, 0);
    }

    /**
     * Removes one local voxel.
     *
     * @param moving vanilla {@code moving} hint, passed through for call-site
     *               compatibility
     * @return the previous state, or {@code null} when nothing was written
     */
    default @Nullable BlockState removeBlock(BlockPos localPos, boolean moving) {
        return setBlock(localPos, Blocks.AIR.defaultBlockState(), moving ? 2 : 3, 0);
    }

    /**
     * Native operation target for vanilla block operations.
     *
     * <p>Server views return the auxiliary void level and its block position for
     * this structure-local voxel. A future redirector can pass them straight to
     * vanilla {@code ServerPlayerGameMode}, {@code BlockState#useItemOn} or
     * {@code destroyBlock}. Returns {@code null} for client views, outside the
     * volume, or when the region/chunk is not currently loaded (so using the
     * target never triggers a synchronous chunk load).</p>
     */
    default @Nullable StructureOperationTarget operationTarget(BlockPos localPos) {
        return null;
    }

    /**
     * {@link #operationTarget(BlockPos, Vec3, Direction)} for a hit result whose
     * position is already in structure-local voxel coordinates (for example a
     * hit produced by {@link StructureBlockGetter#clip}).
     */
    default @Nullable StructureOperationTarget operationTarget(BlockHitResult localHit) {
        if (localHit == null) {
            return null;
        }
        return operationTarget(localHit.getBlockPos(), localHit.getLocation(),
                localHit.getDirection());
    }

    /**
     * Native operation target with the local hit location and face preserved.
     * The returned hit result is rebuilt in auxiliary-level coordinates.
     */
    default @Nullable StructureOperationTarget operationTarget(BlockPos localPos,
                                                               Vec3 localHitLocation,
                                                               Direction direction) {
        return operationTarget(localPos);
    }

    // ------------------------------------------------------------------
    // World projection.
    // ------------------------------------------------------------------

    /** Maps a local metre-space point to world space. */
    default Vector3d localToWorld(Vector3dc localMetres, Vector3d dest) {
        return pose().transformPoint(localMetres, dest);
    }

    /** Maps a world-space point to local metre space. */
    default Vector3d worldToLocal(Vector3dc world, Vector3d dest) {
        return dest.set(pose().inverseTransformPoint(world));
    }

    /** World-space centre of a local voxel. */
    default Vector3d voxelCenterToWorld(int x, int y, int z, Vector3d dest) {
        return localToWorld(new Vector3d(
                (x + 0.5) * blockScale(),
                (y + 0.5) * blockScale(),
                (z + 0.5) * blockScale()), dest);
    }

    /** Continuous local voxel coordinates for a world point. */
    default Vector3d worldToVoxel(Vector3dc world, Vector3d dest) {
        return worldToLocal(world, dest)
                .mul(1.0 / blockScale())
                .sub(0.5, 0.5, 0.5);
    }

    /** World block position containing the centre of a local voxel. */
    default BlockPos localBlockToWorld(BlockPos localBlock) {
        final Vector3d world = voxelCenterToWorld(localBlock.getX(), localBlock.getY(),
                localBlock.getZ(), new Vector3d());
        return BlockPos.containing(world.x, world.y, world.z);
    }

    /** Nearest local voxel for a world block position. */
    default BlockPos worldBlockToLocal(BlockPos worldBlock) {
        final Vector3d voxel = worldToVoxel(new Vector3d(
                worldBlock.getX() + 0.5,
                worldBlock.getY() + 0.5,
                worldBlock.getZ() + 0.5), new Vector3d());
        return new BlockPos(
                (int) Math.floor(voxel.x + 0.5),
                (int) Math.floor(voxel.y + 0.5),
                (int) Math.floor(voxel.z + 0.5));
    }

    /** World position used for sounds emitted by a local voxel. */
    default Vec3 soundPosition(BlockPos localPos) {
        final Vector3d world = voxelCenterToWorld(localPos.getX(), localPos.getY(),
                localPos.getZ(), new Vector3d());
        return new Vec3(world.x, world.y, world.z);
    }

    /** World position used for a particle authored in local metre space. */
    default Vec3 particlePosition(Vector3dc localMetres) {
        final Vector3d world = localToWorld(localMetres, new Vector3d());
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * Plays a sound at the projected world position of a local voxel. No-op for
     * a view without a host level.
     */
    default void playSound(@Nullable Player except, BlockPos localPos, SoundEvent sound,
                           SoundSource source, float volume, float pitch) {
        final Level host = level();
        if (host == null || sound == null) {
            return;
        }
        final Vec3 position = soundPosition(localPos);
        host.playSound(except, position.x, position.y, position.z, sound, source, volume, pitch);
    }

    /**
     * Emits a particle at a projected world position. Coordinates are local
     * metres, matching {@code BlockState#randomTick} particle calls inside a
     * structure frame.
     */
    default void addParticle(ParticleOptions particle, double x, double y, double z,
                             double dx, double dy, double dz) {
        final Level host = level();
        if (host == null || particle == null) {
            return;
        }
        final Vec3 position = particlePosition(new Vector3d(x, y, z));
        host.addParticle(particle, position.x, position.y, position.z, dx, dy, dz);
    }

    // ------------------------------------------------------------------
    // Shapes and collision queries, all in local voxel coordinates.
    // ------------------------------------------------------------------

    /** Read-only {@code BlockGetter} adapter over this view. */
    default StructureBlockGetter blockGetter() {
        return new StructureBlockGetter(this);
    }

    /** Read-only {@code CollisionGetter} adapter over this view. */
    default StructureCollisionGetter collisionGetter() {
        return new StructureCollisionGetter(this);
    }

    /**
     * Block-local outline shape of one voxel, resolved with exact vanilla shape
     * rules and structure-local neighbours.
     */
    default VoxelShape localShape(int x, int y, int z, @Nullable CollisionContext context) {
        final BlockState state = getBlockState(new BlockPos(x, y, z));
        if (state.isAir()) {
            return Shapes.empty();
        }
        return state.getShape(blockGetter(), new BlockPos(x, y, z),
                context == null ? CollisionContext.empty() : context);
    }

    /**
     * Block-local collision shape of one voxel, resolved with exact vanilla
     * shape rules and structure-local neighbours.
     */
    default VoxelShape localCollisionShape(int x, int y, int z,
                                           @Nullable CollisionContext context) {
        final BlockState state = getBlockState(new BlockPos(x, y, z));
        if (state.isAir()) {
            return Shapes.empty();
        }
        return state.getCollisionShape(blockGetter(), new BlockPos(x, y, z),
                context == null ? CollisionContext.empty() : context);
    }

    /**
     * Collision shapes overlapping a local-coordinate box. Shapes are returned
     * already offset by their voxel position, i.e. in the same local metre
     * space as {@code localBox}.
     */
    default List<VoxelShape> getBlockCollisions(@Nullable Entity entity, AABB localBox) {
        return getCollisions(entity, localBox);
    }

    /** {@link #getBlockCollisions(Entity, AABB)} for a ready collision context. */
    default List<VoxelShape> getCollisions(@Nullable Entity entity, AABB localBox) {
        return getCollisions(entity == null ? CollisionContext.empty()
                : CollisionContext.of(entity), localBox);
    }

    /** Collision shapes for an explicit context. */
    default List<VoxelShape> getCollisions(CollisionContext context, AABB localBox) {
        if (localBox == null) {
            return List.of();
        }
        final IntBounds3 bounds = bounds();
        final CollisionContext effective = context == null ? CollisionContext.empty() : context;
        final List<VoxelShape> shapes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(localBox)) {
            if (!bounds.contains(pos.getX(), pos.getY(), pos.getZ())) {
                continue;
            }
            final BlockState state = getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            final VoxelShape shape = state.getCollisionShape(blockGetter(), pos, effective);
            if (!shape.isEmpty()) {
                shapes.add(shape.move(pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        return List.copyOf(shapes);
    }
}
