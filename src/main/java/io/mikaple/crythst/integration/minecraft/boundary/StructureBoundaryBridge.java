package io.mikaple.crythst.integration.minecraft.boundary;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.mixin.boundary.HopperBoundaryAccessor;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host-world bridge for physics structures.
 *
 * <p>Crythst structures live in an auxiliary void level. This bridge is the
 * deliberately small, explicit cross-boundary graph between that copy and the
 * host world. It covers four interactions:</p>
 *
 * <ul>
 *   <li><b>Redstone</b>: host neighbour changes wake the touching structure
 *       voxels; structure changes wake touching host blocks; signal reads are
 *       answered from the structure copy (including structure-to-structure
 *       adjacency).</li>
 *   <li><b>Hoppers/containers</b>: vanilla hoppers inside the void copy pull
 *       from / push into host containers through the auxiliary level read
 *       fallbacks, while host hoppers adjacent to a structure container are
 *       driven by the same one-item transfer routine.</li>
 *   <li><b>Fluids</b>: a bounded per-tick policy spills structure fluid into a
 *       replaceable host block, mirrors host fluid into a replaceable structure
 *       cell, and removes mirrors when the structure supply disappears.</li>
 *   <li><b>Game events</b>: relevant events are forwarded across exposed faces
 *       with a depth guard.</li>
 * </ul>
 *
 * <p>All work is bounded per tick and non-loading: host and auxiliary chunks are
 * read through {@code getChunkNow} only. Auxiliary region coordinates never
 * leave this class or {@link EmptyPhysicsLevel}.</p>
 */
public final class StructureBoundaryBridge {
    public static final int MAX_HOST_NEIGHBOR_LOOKUPS_PER_TICK = 4_096;
    public static final int MAX_AUX_NEIGHBOR_LOOKUPS_PER_TICK = 4_096;
    public static final int MAX_HOST_HOPPER_TRANSFERS_PER_TICK = 256;
    public static final int MAX_FLUID_FACE_SCANS_PER_TICK = 2_048;
    public static final int MAX_FLUID_TO_HOST_PER_TICK = 128;
    public static final int MAX_FLUID_TO_STRUCTURE_PER_TICK = 128;
    public static final int MAX_GAME_EVENTS_PER_TICK = 256;
    public static final int MAX_TOUCHING_VOXELS = 64;
    public static final int MAX_HOST_HOPPER_SCAN_CHUNKS = 512;
    public static final int BRIDGED_FLUID_TTL_TICKS = 400;

    private final LevelPhysics physics;
    private final ServerLevel host;
    private final BoundaryUpdateGuard guard = new BoundaryUpdateGuard();
    private final BoundaryWorkBudget hostNeighborBudget =
            new BoundaryWorkBudget(MAX_HOST_NEIGHBOR_LOOKUPS_PER_TICK);
    private final BoundaryWorkBudget auxNeighborBudget =
            new BoundaryWorkBudget(MAX_AUX_NEIGHBOR_LOOKUPS_PER_TICK);
    private final BoundaryWorkBudget hopperBudget =
            new BoundaryWorkBudget(MAX_HOST_HOPPER_TRANSFERS_PER_TICK);
    private final BoundaryWorkBudget fluidScanBudget =
            new BoundaryWorkBudget(MAX_FLUID_FACE_SCANS_PER_TICK);
    private final BoundaryWorkBudget fluidToHostBudget =
            new BoundaryWorkBudget(MAX_FLUID_TO_HOST_PER_TICK);
    private final BoundaryWorkBudget fluidToStructureBudget =
            new BoundaryWorkBudget(MAX_FLUID_TO_STRUCTURE_PER_TICK);
    private final BoundaryWorkBudget gameEventBudget =
            new BoundaryWorkBudget(MAX_GAME_EVENTS_PER_TICK);
    private final Map<Long, StructureBoundaryIndex> indices = new HashMap<>();
    private final Map<Long, HostHopperCache> hostHoppers = new HashMap<>();
    private final Map<Long, Integer> fluidCursors = new HashMap<>();
    private final Map<Long, BridgedFluid> bridgedHostFluids = new HashMap<>();
    private final Set<Long> hostNeighborDedupe = new HashSet<>();
    private final Set<Long> auxNeighborDedupe = new HashSet<>();
    private final Set<Long> hostPositionsWrittenFromStructure = new HashSet<>();
    private final Set<Long> auxPositionsWrittenFromHost = new HashSet<>();
    private long tick;
    private long lastFailureLogTick = Long.MIN_VALUE;
    private long failureCount;
    private String lastFailure = "";
    private long hostNeighborNotifications;
    private long auxNeighborNotifications;
    private long hostNeighborForwards;
    private long auxNeighborForwards;
    private long hopperPulls;
    private long hopperPushes;
    private long fluidToHost;
    private long fluidToStructure;
    private long fluidRemovals;
    private long gameEventsToStructure;
    private long gameEventsToHost;
    private long droppedWork;

    public StructureBoundaryBridge(LevelPhysics physics) {
        if (physics == null) {
            throw new NullPointerException("physics");
        }
        this.physics = physics;
        this.host = physics.level();
    }

    public BoundaryUpdateGuard guard() {
        return guard;
    }

    // ---------------------------------------------------------------------
    // Tick phases
    // ---------------------------------------------------------------------

    /**
     * Runs after the auxiliary block-logic pass mirrored its edits back into
     * the structure volumes. All phases are budgeted and non-loading.
     */
    public void tick() {
        if (physics.closed()) {
            return;
        }
        tick++;
        guard.resetPerTick();
        hostNeighborBudget.reset();
        auxNeighborBudget.reset();
        hopperBudget.reset();
        fluidScanBudget.reset();
        fluidToHostBudget.reset();
        fluidToStructureBudget.reset();
        gameEventBudget.reset();
        hostNeighborDedupe.clear();
        auxNeighborDedupe.clear();
        hostPositionsWrittenFromStructure.clear();
        auxPositionsWrittenFromHost.clear();
        discardStaleCaches();
        tickHostHoppers();
        tickFluidBoundary();
        pruneBridgedFluids();
    }

    private void discardStaleCaches() {
        final List<PhysicsStructure> structures = physics.structures();
        final Set<Long> live = new HashSet<>();
        for (PhysicsStructure structure : structures) {
            if (!structure.closed()) {
                live.add(structure.body().id());
            }
        }
        indices.keySet().removeIf(id -> !live.contains(id));
        hostHoppers.keySet().removeIf(id -> !live.contains(id));
        fluidCursors.keySet().removeIf(id -> !live.contains(id));
    }

    /** Cleans per-structure caches when a body is removed. */
    public void onStructureRemoved(PhysicsStructure structure) {
        if (structure == null) {
            return;
        }
        final long id = structure.body().id();
        indices.remove(id);
        hostHoppers.remove(id);
        fluidCursors.remove(id);
    }

    /** Clears every cache. Called when the physics world is closed. */
    public void clear() {
        indices.clear();
        hostHoppers.clear();
        fluidCursors.clear();
        bridgedHostFluids.clear();
        hostNeighborDedupe.clear();
        auxNeighborDedupe.clear();
        hostPositionsWrittenFromStructure.clear();
        auxPositionsWrittenFromHost.clear();
        guard.clear();
    }

    // ---------------------------------------------------------------------
    // Host -> structure
    // ---------------------------------------------------------------------

    /**
     * Delivers a host neighbour update to every structure voxel that physically
     * touches the changed host block.
     */
    public void onHostNeighborUpdate(ServerLevel level, BlockPos pos, Block changedBlock) {
        if (level != host || pos == null || changedBlock == null || physics.closed()) {
            return;
        }
        if (hostPositionsWrittenFromStructure.contains(pos.asLong())) {
            return;
        }
        if (guard.isHostToStructureDispatch()) {
            // A mod/block inside the copy triggered a host update while we
            // were delivering host -> structure. Do not echo it back.
            return;
        }
        if (!hostNeighborBudget.tryConsume()) {
            droppedWork++;
            return;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        // A host neighbour update can itself be caused by a structure output
        // propagating through a host wire. Allow that chain (redstone networks
        // legitimately cascade) but never bounce straight back to the body that
        // originated the current structure->host pass.
        final long origin = guard.isStructureToHostDispatch()
                ? guard.currentOriginStructureId() : -1L;
        final List<PhysicsStructure> structures = physics.structures();
        for (PhysicsStructure structure : structures) {
            if (structure.closed() || structure.body().id() == origin
                    || !near(structure, pos)) {
                continue;
            }
            for (StructureVoxel voxel : touchingVoxels(structure, pos)) {
                final BlockPos auxiliaryPos = voxel.auxiliaryPos();
                if (auxiliaryPos == null) {
                    continue;
                }
                if (!auxNeighborDedupe.add(neighborKey(auxiliaryPos, changedBlock))) {
                    continue;
                }
                guard.beginHostToStructure();
                try {
                    auxiliary.level().neighborChanged(auxiliaryPos, changedBlock, null);
                    hostNeighborNotifications++;
                } catch (Throwable failure) {
                    recordFailure("host->structure neighbour", failure);
                } finally {
                    guard.endHostToStructure();
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Structure -> host
    // ---------------------------------------------------------------------

    /**
     * Forwards a neighbour update emitted by a structure voxel to touching host
     * blocks. Called after the auxiliary level's own neighbour notification.
     */
    public void onAuxNeighborUpdate(BlockPos auxiliaryPos, Block changedBlock) {
        if (auxiliaryPos == null || changedBlock == null || physics.closed()) {
            return;
        }
        if (guard.isHostToStructureDispatch()) {
            // This update is part of a host->structure delivery. Let vanilla
            // finish inside the copy; do not bounce it back out.
            return;
        }
        if (!auxNeighborBudget.tryConsume()) {
            droppedWork++;
            return;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        final RegionLookup lookup = regionAtAuxiliary(auxiliaryPos);
        if (lookup == null) {
            return;
        }
        final PhysicsStructure structure = lookup.structure();
        final IntBounds3 bounds = structure.volume().bounds();
        for (Direction direction : Direction.values()) {
            final int outsideX = lookup.localX() + direction.getStepX();
            final int outsideY = lookup.localY() + direction.getStepY();
            final int outsideZ = lookup.localZ() + direction.getStepZ();
            if (bounds.contains(outsideX, outsideY, outsideZ)) {
                // Internal face: not reachable from the host world.
                continue;
            }
            final BlockPos hostPos = BoundaryFaceMapping.hostBlock(
                    structure, outsideX, outsideY, outsideZ);
            if (hostStateAt(hostPos) == null) {
                continue;
            }
            hostPositionsWrittenFromStructure.add(hostPos.asLong());
            if (!hostNeighborDedupe.add(neighborKey(hostPos, changedBlock))) {
                continue;
            }
            if (!guard.beginStructureToHost(structure.body().id())) {
                droppedWork++;
                continue;
            }
            try {
                host.neighborChanged(hostPos, changedBlock, null);
                auxNeighborNotifications++;
            } catch (Throwable failure) {
                recordFailure("structure->host neighbour", failure);
            } finally {
                guard.endStructureToHost();
            }
            // Structure <-> structure: also deliver directly to a touching
            // voxel of another body. The host world has no block there, so a
            // host neighbour update alone would miss it.
            final StructureVoxel other = findStructureVoxel(hostPos);
            if (other != null && other.structure() != structure) {
                final BlockPos otherAux = other.auxiliaryPos();
                if (otherAux != null && auxNeighborDedupe.add(
                        neighborKey(otherAux, changedBlock))) {
                    guard.beginHostToStructure();
                    try {
                        auxiliary.level().neighborChanged(otherAux, changedBlock, null);
                        hostNeighborForwards++;
                    } catch (Throwable failure) {
                        recordFailure("structure<->structure neighbour", failure);
                    } finally {
                        guard.endHostToStructure();
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Redstone reads
    // ---------------------------------------------------------------------

    /**
     * Answers {@code Level#getSignal} for a host position occupied by a
     * structure voxel.
     *
     * @return the signal, or {@code -1} when the host world should answer
     *         normally
     */
    public int hostSignal(ServerLevel level, BlockPos pos, Direction direction) {
        if (level != host || pos == null || direction == null || physics.closed()) {
            return -1;
        }
        if (!guard.beginSignal()) {
            droppedWork++;
            return 0;
        }
        try {
            final BlockState hostState = hostStateAt(pos);
            if (hostState == null || !hostState.isAir()) {
                return -1;
            }
            final StructureVoxel voxel = findStructureVoxel(pos);
            if (voxel == null) {
                return -1;
            }
            final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
            if (auxiliary == null) {
                return -1;
            }
            final Direction localDirection = BoundaryFaceMapping.localDirection(
                    voxel.structure(), direction);
            return BoundarySignals.signalOf(voxel.live().state(), auxiliary.level(),
                    voxel.live().pos(), localDirection);
        } catch (Throwable failure) {
            recordFailure("host signal read", failure);
            return -1;
        } finally {
            guard.endSignal();
        }
    }

    /**
     * Answers {@code Level#getDirectSignal} for a host position occupied by a
     * structure voxel.
     *
     * @return the direct signal, or {@code -1} when the host world should answer
     */
    public int hostDirectSignal(ServerLevel level, BlockPos pos, Direction direction) {
        if (level != host || pos == null || direction == null || physics.closed()) {
            return -1;
        }
        if (!guard.beginSignal()) {
            droppedWork++;
            return 0;
        }
        try {
            final BlockState hostState = hostStateAt(pos);
            if (hostState == null || !hostState.isAir()) {
                return -1;
            }
            final StructureVoxel voxel = findStructureVoxel(pos);
            if (voxel == null) {
                return -1;
            }
            final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
            if (auxiliary == null) {
                return -1;
            }
            final Direction localDirection = BoundaryFaceMapping.localDirection(
                    voxel.structure(), direction);
            return BoundarySignals.directSignalOf(voxel.live().state(), auxiliary.level(),
                    voxel.live().pos(), localDirection);
        } catch (Throwable failure) {
            recordFailure("host direct signal read", failure);
            return -1;
        } finally {
            guard.endSignal();
        }
    }

    /**
     * Signal read from an auxiliary position just outside a structure region.
     * This is what lets redstone wire/torches inside the copy see host levers,
     * buttons, torches and neighbouring structures.
     */
    public int auxiliarySignal(BlockPos auxiliaryPos, Direction localDirection) {
        if (auxiliaryPos == null || localDirection == null || physics.closed()) {
            return 0;
        }
        if (!guard.beginSignal()) {
            droppedWork++;
            return 0;
        }
        try {
            final OutsideLookup lookup = outsideRegionLookup(auxiliaryPos);
            if (lookup == null) {
                return 0;
            }
            final BlockPos hostPos = BoundaryFaceMapping.hostBlock(
                    lookup.structure(), lookup.localX(), lookup.localY(), lookup.localZ());
            final BlockPos insideHost = BoundaryFaceMapping.hostBlock(
                    lookup.structure(),
                    lookup.localX() - localDirection.getStepX(),
                    lookup.localY() - localDirection.getStepY(),
                    lookup.localZ() - localDirection.getStepZ());
            if (hostPos.equals(insideHost)) {
                // No distinct host cell on this face (large/rotated block).
                return 0;
            }
            final Direction worldDirection = BoundaryFaceMapping.worldDirection(
                    lookup.structure(), localDirection);
            hostNeighborForwards++;
            return host.getSignal(hostPos, worldDirection);
        } catch (Throwable failure) {
            recordFailure("auxiliary signal read", failure);
            return 0;
        } finally {
            guard.endSignal();
        }
    }

    /** Direct-signal variant of {@link #auxiliarySignal}. */
    public int auxiliaryDirectSignal(BlockPos auxiliaryPos, Direction localDirection) {
        if (auxiliaryPos == null || localDirection == null || physics.closed()) {
            return 0;
        }
        if (!guard.beginSignal()) {
            droppedWork++;
            return 0;
        }
        try {
            final OutsideLookup lookup = outsideRegionLookup(auxiliaryPos);
            if (lookup == null) {
                return 0;
            }
            final BlockPos hostPos = BoundaryFaceMapping.hostBlock(
                    lookup.structure(), lookup.localX(), lookup.localY(), lookup.localZ());
            final BlockPos insideHost = BoundaryFaceMapping.hostBlock(
                    lookup.structure(),
                    lookup.localX() - localDirection.getStepX(),
                    lookup.localY() - localDirection.getStepY(),
                    lookup.localZ() - localDirection.getStepZ());
            if (hostPos.equals(insideHost)) {
                // No distinct host cell on this face (large/rotated block).
                return 0;
            }
            final Direction worldDirection = BoundaryFaceMapping.worldDirection(
                    lookup.structure(), localDirection);
            hostNeighborForwards++;
            return host.getDirectSignal(hostPos, worldDirection);
        } catch (Throwable failure) {
            recordFailure("auxiliary direct signal read", failure);
            return 0;
        } finally {
            guard.endSignal();
        }
    }

    // ---------------------------------------------------------------------
    // Auxiliary block/entity/fluid fallbacks
    // ---------------------------------------------------------------------

    /** Host block state for an auxiliary position just outside a region. */
    public @Nullable BlockState hostBlockStateForAuxiliary(BlockPos auxiliaryPos) {
        final OutsideLookup lookup = outsideRegionLookup(auxiliaryPos);
        if (lookup == null) {
            return null;
        }
        final BlockPos hostPos = BoundaryFaceMapping.hostBlock(
                lookup.structure(), lookup.localX(), lookup.localY(), lookup.localZ());
        final BlockState state = hostStateAt(hostPos);
        return state == null || state.isAir() ? null : state;
    }

    /** Host block entity for an auxiliary boundary position. */
    public @Nullable BlockEntity hostBlockEntityForAuxiliary(BlockPos auxiliaryPos) {
        final OutsideLookup lookup = outsideRegionLookup(auxiliaryPos);
        if (lookup == null) {
            return null;
        }
        final BlockPos hostPos = BoundaryFaceMapping.hostBlock(
                lookup.structure(), lookup.localX(), lookup.localY(), lookup.localZ());
        return hostBlockEntityAt(hostPos);
    }

    /** Host fluid state for an auxiliary boundary position. */
    public @Nullable FluidState hostFluidStateForAuxiliary(BlockPos auxiliaryPos) {
        final OutsideLookup lookup = outsideRegionLookup(auxiliaryPos);
        if (lookup == null) {
            return null;
        }
        final BlockPos hostPos = BoundaryFaceMapping.hostBlock(
                lookup.structure(), lookup.localX(), lookup.localY(), lookup.localZ());
        final BlockState state = hostStateAt(hostPos);
        if (state == null || state.isAir()) {
            return null;
        }
        return state.getFluidState();
    }

    // ---------------------------------------------------------------------
    // Game events
    // ---------------------------------------------------------------------

    /** Forwards a host game event to structures whose volume contains it. */
    public void onHostGameEvent(ServerLevel level, Holder<GameEvent> event,
                                Vec3 position, GameEvent.Context context) {
        if (level != host || event == null || position == null || physics.closed()) {
            return;
        }
        if (guard.isGameEventToHostDispatch()) {
            return;
        }
        if (guard.isGameEventToStructureDispatch()) {
            // Already delivering a host event into the copy; a listener that
            // re-enters the host must not start a second round trip.
            return;
        }
        if (io.mikaple.crythst.integration.minecraft.gameevent.StructureGameEvents
                .isBridgeActive()) {
            // This host event is already the projection of an auxiliary event.
            // Forwarding it back into the copy would echo.
            return;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed() || !nearPoint(structure, position)) {
                continue;
            }
            if (!gameEventBudget.tryConsume()) {
                droppedWork++;
                return;
            }
            final EmptyPhysicsLevel.RegionView region = auxiliary.regionView(structure);
            if (region == null) {
                continue;
            }
            final Vector3d local = structure.worldToVoxel(new Vector3d(), new Vector3d(
                    position.x, position.y, position.z));
            final IntBounds3 bounds = structure.volume().bounds();
            if (local.x < bounds.minX() - 1.0 || local.x > bounds.maxX()
                    || local.y < bounds.minY() - 1.0 || local.y > bounds.maxY()
                    || local.z < bounds.minZ() - 1.0 || local.z > bounds.maxZ()) {
                continue;
            }
            guard.beginGameEventToStructure();
            try {
                auxiliary.level().gameEvent(event,
                        region.toAuxiliaryPoint(local.x, local.y, local.z), context);
                gameEventsToStructure++;
            } catch (Throwable failure) {
                recordFailure("host->structure game event", failure);
            } finally {
                guard.endGameEventToStructure();
            }
        }
    }

    /** Forwards an auxiliary game event to the host world. */
    public void onAuxiliaryGameEvent(Holder<GameEvent> event, Vec3 auxiliaryPosition,
                                     GameEvent.Context context) {
        if (event == null || auxiliaryPosition == null || physics.closed()) {
            return;
        }
        if (guard.isGameEventToStructureDispatch()) {
            return;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        final Vec3 projected = auxiliary.projectAuxiliaryPoint(
                auxiliaryPosition.x, auxiliaryPosition.y, auxiliaryPosition.z);
        if (projected == null) {
            return;
        }
        if (!gameEventBudget.tryConsume()) {
            droppedWork++;
            return;
        }
        guard.beginGameEventToHost();
        try {
            host.gameEvent(event, projected, context);
            gameEventsToHost++;
        } catch (Throwable failure) {
            recordFailure("structure->host game event", failure);
        } finally {
            guard.endGameEventToHost();
        }
    }

    // ---------------------------------------------------------------------
    // Hopper / container boundary
    // ---------------------------------------------------------------------

    /**
     * Vanilla hoppers inside the void copy work through the auxiliary level's
     * state/block-entity fallbacks. This phase covers the other direction: a
     * real host hopper next to a structure container.
     */
    private void tickHostHoppers() {
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed()) {
                continue;
            }
            for (BlockPos hopperPos : hostHoppersFor(structure)) {
                if (!hopperBudget.tryConsume()) {
                    droppedWork++;
                    return;
                }
                final LevelChunk chunk = hostChunkNow(hopperPos);
                if (chunk == null) {
                    continue;
                }
                final BlockState state = chunk.getBlockState(hopperPos);
                if (!state.is(Blocks.HOPPER)) {
                    continue;
                }
                if (!state.getValue(HopperBlock.ENABLED)) {
                    continue;
                }
                if (!(chunk.getBlockEntity(hopperPos) instanceof HopperBlockEntity hopper)) {
                    continue;
                }
                if (!(hopper instanceof HopperBoundaryAccessor accessor)) {
                    // The cooldown accessor is a required mixin; skip rather
                    // than risk double-moving items if it is unavailable.
                    continue;
                }
                if (accessor.crythst$isOnCooldown()) {
                    continue;
                }
                final Direction facing = state.getValue(HopperBlock.FACING);
                if (tryHostHopperPull(hopperPos, hopper, accessor)) {
                    continue;
                }
                tryHostHopperPush(hopperPos, hopper, accessor, facing);
            }
        }
    }

    private boolean tryHostHopperPull(BlockPos hopperPos, HopperBlockEntity hopper,
                                      HopperBoundaryAccessor accessor) {
        final StructureVoxel above = findStructureVoxel(hopperPos.above());
        if (above == null || !(above.live().blockEntity() instanceof Container source)) {
            return false;
        }
        if (!HopperTransfer.pull(source, hopper)) {
            return false;
        }
        accessor.crythst$setCooldown(8);
        hopper.setChanged();
        hopperPulls++;
        return true;
    }

    private boolean tryHostHopperPush(BlockPos hopperPos, HopperBlockEntity hopper,
                                      HopperBoundaryAccessor accessor, Direction facing) {
        final StructureVoxel target = findStructureVoxel(hopperPos.relative(facing));
        if (target == null || !(target.live().blockEntity() instanceof Container container)) {
            return false;
        }
        if (!HopperTransfer.push(hopper, container, facing.getOpposite())) {
            return false;
        }
        accessor.crythst$setCooldown(8);
        hopper.setChanged();
        hopperPushes++;
        return true;
    }

    private List<BlockPos> hostHoppersFor(PhysicsStructure structure) {
        final long id = structure.body().id();
        final long now = tick;
        final HostHopperCache cached = hostHoppers.get(id);
        if (cached != null && now - cached.scanTick() < 20L) {
            return cached.positions();
        }
        final List<BlockPos> positions = scanHostHoppers(structure);
        hostHoppers.put(id, new HostHopperCache(now, positions));
        return positions;
    }

    private List<BlockPos> scanHostHoppers(PhysicsStructure structure) {
        final var aabb = structure.aabb().expand(2.0);
        final int minChunkX = (int) Math.floor(aabb.min().x) >> 4;
        final int minChunkZ = (int) Math.floor(aabb.min().z) >> 4;
        final int maxChunkX = (int) Math.floor(aabb.max().x) >> 4;
        final int maxChunkZ = (int) Math.floor(aabb.max().z) >> 4;
        List<BlockPos> positions = null;
        int scanned = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (++scanned > MAX_HOST_HOPPER_SCAN_CHUNKS) {
                    return positions == null ? List.of() : positions;
                }
                final LevelChunk chunk = host.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof HopperBlockEntity && !blockEntity.isRemoved()) {
                        if (positions == null) {
                            positions = new ArrayList<>();
                        }
                        positions.add(blockEntity.getBlockPos());
                        if (positions.size() >= MAX_HOST_HOPPER_SCAN_CHUNKS * 4) {
                            return positions;
                        }
                    }
                }
            }
        }
        return positions == null ? List.of() : positions;
    }

    // ---------------------------------------------------------------------
    // Fluid boundary
    // ---------------------------------------------------------------------

    private void tickFluidBoundary() {
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return;
        }
        final List<PhysicsStructure> structures = physics.structures();
        final int structureCount = Math.max(1, structures.size());
        final int perStructureLimit = Math.max(32,
                MAX_FLUID_FACE_SCANS_PER_TICK / structureCount);
        for (PhysicsStructure structure : structures) {
            if (structure.closed()) {
                continue;
            }
            final StructureBoundaryIndex index = indexFor(structure);
            final List<BoundaryFace> faces = index.faces();
            if (faces.isEmpty()) {
                continue;
            }
            final long id = structure.body().id();
            final int cursor = Math.floorMod(fluidCursors.getOrDefault(id, 0), faces.size());
            int processed = 0;
            for (int i = 0; i < faces.size() && processed < perStructureLimit; i++) {
                if (!fluidScanBudget.tryConsume()) {
                    droppedWork++;
                    fluidCursors.put(id, Math.floorMod(cursor + i, faces.size()));
                    return;
                }
                final BoundaryFace face = faces.get((cursor + i) % faces.size());
                processed++;
                processFluidFace(structure, auxiliary, face);
            }
            fluidCursors.put(id, Math.floorMod(cursor + processed, faces.size()));
        }
    }

    private void processFluidFace(PhysicsStructure structure, EmptyPhysicsLevel auxiliary,
                                  BoundaryFace face) {
        final BlockState insideState = StructureBoundaryIndex.stateAt(
                structure, face.x(), face.y(), face.z());
        final BlockPos hostPos = BoundaryFaceMapping.hostBlockOutside(structure, face);
        if (hostPos.equals(BoundaryFaceMapping.hostBlock(
                structure, face.x(), face.y(), face.z()))) {
            // Large/rotated blocks can make the outside face map back into the
            // boundary voxel's own host block. There is no distinct host cell
            // to exchange fluid with.
            return;
        }
        final BlockState hostState = hostStateAt(hostPos);
        if (hostState == null) {
            return;
        }
        final BlockState inside = insideState == null
                ? Blocks.AIR.defaultBlockState() : insideState;
        final FluidState insideFluid = inside.getFluidState();
        final FluidState hostFluid = hostState.getFluidState();
        final long hostKey = hostPos.asLong();
        final BridgedFluid bridged = bridgedHostFluids.get(hostKey);
        final boolean bridgeOwned = bridged != null
                && hostState.getBlock() == bridged.state().getBlock();
        final FluidBoundaryPolicy.Action action = FluidBoundaryPolicy.decide(
                inside, hostState, insideFluid, hostFluid, bridgeOwned);
        switch (action) {
            case FLUID_TO_HOST -> fluidToHost(structure, auxiliary, face, hostPos,
                    hostState, insideFluid);
            case FLUID_TO_STRUCTURE -> fluidToStructure(structure, auxiliary, face,
                    hostPos, hostFluid, hostKey);
            case REMOVE_HOST_FLUID -> removeBridgedHostFluid(structure, hostPos, bridged);
            case NONE -> {
                if (bridged != null && !insideFluid.isEmpty()) {
                    // Still flowing; keep the ownership mark alive.
                    bridgedHostFluids.put(hostKey, new BridgedFluid(bridged.state(), tick));
                }
            }
        }
    }

    private void fluidToHost(PhysicsStructure structure, EmptyPhysicsLevel auxiliary,
                             BoundaryFace face, BlockPos hostPos, BlockState hostState,
                             FluidState insideFluid) {
        final StructureVoxel other = findStructureVoxel(hostPos);
        if (other != null && other.structure() != structure) {
            return;
        }
        if (!hostState.isAir() && !hostState.canBeReplaced()) {
            return;
        }
        if (!fluidToHostBudget.tryConsume()) {
            droppedWork++;
            return;
        }
        if (host.isOutsideBuildHeight(hostPos)) {
            return;
        }
        if (!guard.beginStructureToHost(structure.body().id())) {
            droppedWork++;
            return;
        }
        try {
            final BlockState legacy = insideFluid.createLegacyBlock();
            host.setBlock(hostPos, legacy, 2);
            host.scheduleTick(hostPos, insideFluid.getType(),
                    Math.max(1, insideFluid.getType().getTickDelay(host)));
            bridgedHostFluids.put(hostPos.asLong(), new BridgedFluid(legacy, tick));
            fluidToHost++;
        } catch (Throwable failure) {
            recordFailure("structure->host fluid", failure);
        } finally {
            guard.endStructureToHost();
        }
    }

    private void fluidToStructure(PhysicsStructure structure, EmptyPhysicsLevel auxiliary,
                                  BoundaryFace face, BlockPos hostPos,
                                  FluidState hostFluid, long hostKey) {
        if (auxPositionsWrittenFromHost.contains(hostKey)) {
            return;
        }
        if (!fluidToStructureBudget.tryConsume()) {
            droppedWork++;
            return;
        }
        final EmptyPhysicsLevel.RegionView region = auxiliary.regionView(structure);
        if (region == null) {
            return;
        }
        final BlockPos auxiliaryPos = region.toAuxiliaryVoxel(face.x(), face.y(), face.z());
        if (auxiliaryPos == null || auxiliary.level().isOutsideBuildHeight(auxiliaryPos)) {
            return;
        }
        guard.beginHostToStructure();
        try {
            final BlockState legacy = hostFluid.createLegacyBlock();
            auxiliary.level().setBlock(auxiliaryPos, legacy, 2);
            auxiliary.level().scheduleTick(auxiliaryPos, hostFluid.getType(),
                    Math.max(1, hostFluid.getType().getTickDelay(auxiliary.level())));
            auxPositionsWrittenFromHost.add(hostKey);
            fluidToStructure++;
        } catch (Throwable failure) {
            recordFailure("host->structure fluid", failure);
        } finally {
            guard.endHostToStructure();
        }
    }

    private void removeBridgedHostFluid(PhysicsStructure structure, BlockPos hostPos,
                                        @Nullable BridgedFluid bridged) {
        if (bridged == null || !fluidToHostBudget.tryConsume()) {
            if (bridged != null) {
                droppedWork++;
            }
            return;
        }
        final BlockState current = hostStateAt(hostPos);
        if (current == null || current.getBlock() != bridged.state().getBlock()) {
            bridgedHostFluids.remove(hostPos.asLong());
            return;
        }
        if (!guard.beginStructureToHost(structure.body().id())) {
            return;
        }
        try {
            host.setBlock(hostPos, Blocks.AIR.defaultBlockState(), 2);
            bridgedHostFluids.remove(hostPos.asLong());
            fluidRemovals++;
        } catch (Throwable failure) {
            recordFailure("remove mirrored host fluid", failure);
        } finally {
            guard.endStructureToHost();
        }
    }

    private void pruneBridgedFluids() {
        if (bridgedHostFluids.isEmpty()) {
            return;
        }
        if (bridgedHostFluids.size() > 8_192) {
            bridgedHostFluids.clear();
            droppedWork++;
            return;
        }
        // Active faces refresh their entry every tick. An entry that has not
        // been touched for the TTL belongs to a structure that was removed,
        // moved away, or stopped supplying fluid; forget it instead of leaking
        // the host position forever. The mirrored block itself is removed by
        // the face policy when the structure is still present.
        bridgedHostFluids.entrySet().removeIf(
                entry -> tick - entry.getValue().tick() >= BRIDGED_FLUID_TTL_TICKS);
    }

    // ---------------------------------------------------------------------
    // Structure lookup helpers
    // ---------------------------------------------------------------------

    private @Nullable StructureBoundaryIndex indexFor(PhysicsStructure structure) {
        final long id = structure.body().id();
        final StructureBoundaryIndex cached = indices.get(id);
        if (cached != null && cached.matches(structure)) {
            return cached;
        }
        final StructureBoundaryIndex built = StructureBoundaryIndex.build(structure);
        indices.put(id, built);
        return built;
    }

    /**
     * Nearest non-air structure voxel for a host block. The search is one/two
     * voxels wide because a scaled-down structure has several voxels inside one
     * host block and a rotated face may not align exactly.
     */
    private @Nullable StructureVoxel findStructureVoxel(BlockPos hostPos) {
        if (hostPos == null) {
            return null;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return null;
        }
        final BlockState hostState = hostStateAt(hostPos);
        if (hostState == null || !hostState.isAir()) {
            return null;
        }
        final Vector3d center = new Vector3d(
                hostPos.getX() + 0.5, hostPos.getY() + 0.5, hostPos.getZ() + 0.5);
        StructureVoxel best = null;
        double bestDistance = Double.MAX_VALUE;
        final List<PhysicsStructure> structures = physics.structures();
        for (PhysicsStructure structure : structures) {
            if (structure.closed() || !near(structure, hostPos)) {
                continue;
            }
            final Vector3d local = structure.worldToVoxel(new Vector3d(), center);
            final IntBounds3 bounds = structure.volume().bounds();
            final int radius = BoundaryFaceMapping.searchRadius(structure.blockScale());
            final int baseX = (int) Math.round(local.x);
            final int baseY = (int) Math.round(local.y);
            final int baseZ = (int) Math.round(local.z);
            if (baseX + radius < bounds.minX() || baseX - radius >= bounds.maxX()
                    || baseY + radius < bounds.minY() || baseY - radius >= bounds.maxY()
                    || baseZ + radius < bounds.minZ() || baseZ - radius >= bounds.maxZ()) {
                continue;
            }
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        final int x = baseX + dx;
                        final int y = baseY + dy;
                        final int z = baseZ + dz;
                        if (!bounds.contains(x, y, z)) {
                            continue;
                        }
                        final EmptyPhysicsLevel.LiveBlock live =
                                auxiliary.liveBlock(structure, x, y, z);
                        if (live == null) {
                            continue;
                        }
                        final Vector3d blockCenter = structure.blockCenter(x, y, z);
                        final double distance = blockCenter.distanceSquared(center);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new StructureVoxel(structure, x, y, z, live,
                                    auxiliary.regionView(structure));
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * All structure voxels in one body that a host block can physically touch.
     * Used for neighbour delivery, where notifying only the nearest voxel would
     * miss corner/diagonal contacts.
     */
    private List<StructureVoxel> touchingVoxels(PhysicsStructure structure, BlockPos hostPos) {
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return List.of();
        }
        final Vector3d center = new Vector3d(
                hostPos.getX() + 0.5, hostPos.getY() + 0.5, hostPos.getZ() + 0.5);
        final Vector3d local = structure.worldToVoxel(new Vector3d(), center);
        final IntBounds3 bounds = structure.volume().bounds();
        final double maxDistance = BoundaryFaceMapping.touchingDistance(structure.blockScale());
        final int radius = BoundaryFaceMapping.searchRadius(structure.blockScale());
        final int baseX = (int) Math.floor(local.x);
        final int baseY = (int) Math.floor(local.y);
        final int baseZ = (int) Math.floor(local.z);
        final EmptyPhysicsLevel.RegionView region = auxiliary.regionView(structure);
        List<StructureVoxel> result = null;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    final int x = baseX + dx;
                    final int y = baseY + dy;
                    final int z = baseZ + dz;
                    if (!bounds.contains(x, y, z)) {
                        continue;
                    }
                    if (Math.abs(local.x - x) > maxDistance
                            || Math.abs(local.y - y) > maxDistance
                            || Math.abs(local.z - z) > maxDistance) {
                        continue;
                    }
                    final EmptyPhysicsLevel.LiveBlock live =
                            auxiliary.liveBlock(structure, x, y, z);
                    if (live == null) {
                        continue;
                    }
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(new StructureVoxel(structure, x, y, z, live, region));
                    if (result.size() >= MAX_TOUCHING_VOXELS) {
                        return result;
                    }
                }
            }
        }
        return result == null ? List.of() : result;
    }

    private @Nullable RegionLookup regionAtAuxiliary(BlockPos auxiliaryPos) {
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return null;
        }
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed()) {
                continue;
            }
            final EmptyPhysicsLevel.RegionView region = auxiliary.regionView(structure);
            if (region == null) {
                continue;
            }
            final BlockPos local = region.toLocalVoxel(auxiliaryPos);
            if (local != null) {
                return new RegionLookup(structure, region,
                        local.getX(), local.getY(), local.getZ());
            }
        }
        return null;
    }

    /**
     * Maps an auxiliary position that lies just outside every region to the
     * owner structure and the local voxel that is one step beyond the volume.
     */
    private @Nullable OutsideLookup outsideRegionLookup(BlockPos auxiliaryPos) {
        if (auxiliaryPos == null) {
            return null;
        }
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        if (auxiliary == null) {
            return null;
        }
        OutsideLookup best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed()) {
                continue;
            }
            final EmptyPhysicsLevel.RegionView region = auxiliary.regionView(structure);
            if (region == null) {
                continue;
            }
            if (region.toLocalVoxel(auxiliaryPos) != null) {
                // Inside a copied region: normal auxiliary block state, never a
                // host fallback.
                return null;
            }
            final int localX = auxiliaryPos.getX() - region.baseX() + region.volumeMinX();
            final int localY = auxiliaryPos.getY() - region.baseY() + region.volumeMinY();
            final int localZ = auxiliaryPos.getZ() - region.baseZ() + region.volumeMinZ();
            final int minX = region.volumeMinX() - 1;
            final int minY = region.volumeMinY() - 1;
            final int minZ = region.volumeMinZ() - 1;
            final int maxX = region.volumeMinX() + region.sizeX();
            final int maxY = region.volumeMinY() + region.sizeY();
            final int maxZ = region.volumeMinZ() + region.sizeZ();
            if (localX < minX || localX > maxX
                    || localY < minY || localY > maxY
                    || localZ < minZ || localZ > maxZ) {
                continue;
            }
            final double distance = Math.abs(localX - region.volumeMinX() - region.sizeX() * 0.5)
                    + Math.abs(localY - region.volumeMinY() - region.sizeY() * 0.5)
                    + Math.abs(localZ - region.volumeMinZ() - region.sizeZ() * 0.5);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new OutsideLookup(structure, region, localX, localY, localZ);
            }
        }
        return best;
    }

    private boolean near(PhysicsStructure structure, BlockPos pos) {
        final Vector3d center = new Vector3d(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        final double margin = 0.75 * structure.blockScale() + 1.5;
        return structure.aabb().expand(margin).contains(center);
    }

    private boolean nearPoint(PhysicsStructure structure, Vec3 point) {
        final double margin = 0.75 * structure.blockScale() + 1.5;
        return structure.aabb().expand(margin).contains(
                new Vector3d(point.x, point.y, point.z));
    }

    private @Nullable LevelChunk hostChunkNow(BlockPos pos) {
        return host.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static long neighborKey(BlockPos pos, Block block) {
        return pos.asLong() * 0x9E3779B97F4A7C15L
                ^ ((long) block.hashCode() << 17);
    }

    private @Nullable BlockState hostStateAt(BlockPos pos) {
        final LevelChunk chunk = hostChunkNow(pos);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    private @Nullable BlockEntity hostBlockEntityAt(BlockPos pos) {
        final LevelChunk chunk = hostChunkNow(pos);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    private void recordFailure(String phase, Throwable failure) {
        failureCount++;
        lastFailure = phase + ": " + (failure == null ? "unknown"
                : failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
        final long now = host.getGameTime();
        if (lastFailureLogTick == Long.MIN_VALUE || now - lastFailureLogTick >= 200L) {
            lastFailureLogTick = now;
            Crythst.LOGGER.warn("Structure boundary {} failed in {}",
                    phase, host.dimension().identifier(), failure);
        }
    }

    // ---------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------

    public String status() {
        return "boundary(hostNeighbors=" + hostNeighborNotifications
                + " auxNeighbors=" + auxNeighborNotifications
                + " hostForwards=" + hostNeighborForwards
                + " auxForwards=" + auxNeighborForwards
                + " hopperPulls=" + hopperPulls
                + " hopperPushes=" + hopperPushes
                + " fluidToHost=" + fluidToHost
                + " fluidToStructure=" + fluidToStructure
                + " fluidRemovals=" + fluidRemovals
                + " gameEventsToStructure=" + gameEventsToStructure
                + " gameEventsToHost=" + gameEventsToHost
                + " dropped=" + droppedWork
                + " guardDrops=" + guard.droppedDepth()
                + " bridgedFluids=" + bridgedHostFluids.size()
                + " hostHoppers=" + hostHoppers.size()
                + " failures=" + failureCount
                + " lastFailure=" + (lastFailure.isEmpty() ? "none" : lastFailure)
                + ")";
    }

    private record StructureVoxel(PhysicsStructure structure, int x, int y, int z,
                                  EmptyPhysicsLevel.LiveBlock live,
                                  @Nullable EmptyPhysicsLevel.RegionView region) {
        @Nullable BlockPos auxiliaryPos() {
            return region == null ? null : region.toAuxiliaryVoxel(x, y, z);
        }
    }

    private record RegionLookup(PhysicsStructure structure,
                                EmptyPhysicsLevel.RegionView region,
                                int localX, int localY, int localZ) {
    }

    private record OutsideLookup(PhysicsStructure structure,
                                 EmptyPhysicsLevel.RegionView region,
                                 int localX, int localY, int localZ) {
    }

    private record HostHopperCache(long scanTick, List<BlockPos> positions) {
        private HostHopperCache {
            positions = List.copyOf(positions);
        }
    }

    private record BridgedFluid(BlockState state, long tick) {
    }
}
