package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.CrythstEvents;
import io.mikaple.crythst.api.block.StructureBlockHooks;
import io.mikaple.crythst.api.event.PhysicsTickEvent;
import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.api.physics.StructurePhysicsRuntime;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.entity.attachment.StructureAttachedEntities;
import io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.tick.PhysicsTimingMonitor;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.world.CcdMotionGuard;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.StepListener;
import io.mikaple.crythst.physics.world.TerrainCollisionSnapshot;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A physics world attached to one Minecraft dimension.
 */
public final class LevelPhysics implements AutoCloseable {
    private final ServerLevel level;
    private final PhysicsEngine engine;
    private final CrythstConfig config;
    private final PhysicsWorld world;
    private final TerrainCollision terrain;
    private final UnstuckResolver unstuck;
    private final List<PhysicsStructure> structures = new ArrayList<>();
    private final PhysicsTimingMonitor timing = new PhysicsTimingMonitor();
    private final PhysicsPipeline apiPipeline;
    private final StructurePhysicsRuntime structureActors;
    private final StructureAttachedEntities attachedEntities;
    private long physicsTickIndex;

    /**
     * Auxiliary void level for block logic. Created lazily on the first
     * structure so dimensions without physics structures never allocate a
     * level or load chunks.
     */
    private EmptyPhysicsLevel subLevel;
    private boolean subLevelCreationFailed;
    private EmptyPhysicsLevel.TickResult lastVoidTick = EmptyPhysicsLevel.TickResult.EMPTY;
    private int voidTickLogCounter;

    /**
     * Last auxiliary-entity projection pass. Entities spawned by dispensers and
     * other block logic in the void level are moved to this level after each
     * sub-level tick.
     */
    private StructureEntityProjection.Batch lastEntityProjection =
            StructureEntityProjection.Batch.EMPTY;
    private long projectedEntityCount;
    /** Failed projections are retried after a short cooldown, not every tick. */
    private final Map<UUID, Long> entityProjectionRetryAfter = new HashMap<>();
    private boolean entityProjectionFailureLogged;

    private volatile List<PhysicsStructure> renderStructures = List.of();
    private volatile io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot renderDebugSnapshot;

    private long lastTickNanos = System.nanoTime();
    private boolean closed;

    LevelPhysics(ServerLevel level, PhysicsEngine engine, CrythstConfig config) {
        this.level = level;
        this.engine = engine;
        this.config = config;
        this.world = engine.createWorld(WorldSettings.defaults(new Vector3d())
                .withFixedTimeStep(config.fixedTimeStep)
                .withMaxSubSteps(config.maxSubSteps)
                .withSolverIterations(config.solverIterations)
                .withSleep(config.allowSleep)
                .withDeterministic(config.deterministic));
        this.terrain = new TerrainCollision(level, world, config);
        this.unstuck = new UnstuckResolver(level, world, config);
        this.apiPipeline = new PhysicsPipeline(this);
        this.structureActors = new StructurePhysicsRuntime(level);
        this.attachedEntities = new StructureAttachedEntities(level);
        this.world.addStepListener(new StepListener() {
            @Override
            public void beforeStep(PhysicsWorld steppedWorld, double fixedTimeStep) {
                CrythstEvents.firePrePhysicsTick(new PhysicsTickEvent(
                        level, apiPipeline, fixedTimeStep, physicsTickIndex));
                // Run block-entity actors after the public pre-step event so
                // existing listener ordering is unchanged. Both paths run
                // before integration and see the same substep length.
                for (int i = 0; i < structures.size(); i++) {
                    structureActors.runSubstep(structures.get(i), fixedTimeStep);
                }
            }

            @Override
            public void afterStep(PhysicsWorld steppedWorld, double fixedTimeStep,
                                  io.mikaple.crythst.physics.world.StepStats stats) {
                CrythstEvents.firePostPhysicsTick(new PhysicsTickEvent(
                        level, apiPipeline, fixedTimeStep, physicsTickIndex));
                physicsTickIndex++;
            }
        });
        PlayerGravity.setWorldDirection(level.dimension(), world.gravity());
    }

    /**
     * API view of this level's physics world. Valid until the level unloads.
     */
    public PhysicsPipeline apiPipeline() {
        return apiPipeline;
    }

    public ServerLevel level() {
        return level;
    }

    public PhysicsWorld world() {
        return world;
    }

    public List<PhysicsStructure> structures() {
        return List.copyOf(structures);
    }

    /**
     * Volatile list safe to read from the client render thread. Never call
     * methods on the structures that touch Bullet state from that thread; use
     * {@link PhysicsStructure#renderState(double)} instead.
     */
    public List<PhysicsStructure> renderStructures() {
        return renderStructures;
    }

    public io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot renderDebugSnapshot() {
        return renderDebugSnapshot;
    }

    private void refreshRenderStructures() {
        renderStructures = List.copyOf(structures);
    }

    /**
     * Publishes a block snapshot immediately. Split components get new body ids;
     * without this the client can see them one frame before their snapshot
     * exists and they flash/disappear.
     */
    public void captureSnapshotNow(PhysicsStructure structure) {
        StructureBlockSnapshots.captureNow(level.dimension(), structure);
    }

    public PhysicsStructure createStructure(StructureDef definition) {
        StructureBlockHooks.fireBeforeAssembly(level, definition.volume());
        final PhysicsStructure structure = new PhysicsStructure(world, definition);
        structures.add(structure);
        refreshRenderStructures();
        terrain.ensureNow();
        final EmptyPhysicsLevel sub = subLevel();
        if (sub != null) {
            try {
                sub.setAttachedEntities(attachedEntities);
                sub.setEntityProjector(this::projectVoidEntity);
                sub.addStructure(structure, definition.volume());
                refreshStructureActors(structure);
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Could not copy structure body #{} into the auxiliary void level; "
                        + "its blocks will not tick", structure.body().id(), t);
                try {
                    sub.removeStructure(structure);
                } catch (Throwable cleanupFailure) {
                    Crythst.LOGGER.debug("Could not clean up partial void-level region", cleanupFailure);
                }
            }
        }
        StructureBlockHooks.fireAfterAssembly(level, definition.volume(), structure);
        return structure;
    }

    public void removeStructure(PhysicsStructure structure) {
        structure.invalidateBlockEntityActors();
        structureActors.forget(structure);
        // Release decorations/seats as normal host entities at their last valid
        // pose before the body/region disappears.
        attachedEntities.releaseStructure(structure);
        // Flush entities that the region spawned before its blocks and pose
        // disappear.
        projectSubLevelEntities();
        // The void region is keyed by the body id, so remove it before the
        // physics body is closed.
        if (subLevel != null) {
            try {
                subLevel.removeStructure(structure);
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Error removing structure body #{} from the auxiliary void level",
                        structure.body().id(), t);
            }
        }
        structures.remove(structure);
        refreshRenderStructures();
        structure.close();
    }

    /**
     * Re-copies a structure's blocks after {@link PhysicsStructure#reindex}.
     * Reindexing directly on the structure cannot notify this world, so callers
     * that modify a volume must invoke this method (or the overload taking the
     * new volume) to keep block ticking in sync.
     */
    public List<PhysicsStructure> refreshStructure(PhysicsStructure structure) {
        return refreshStructure(structure, structure.volume());
    }

    /**
     * Re-copies a structure's blocks after a volume change and then repairs its
     * topology.
     *
     * <p>Blocks that no longer form one 26-connected component become
     * independent bodies, and a body whose remaining mass is zero is removed.
     * The caller must not keep using {@code structure} after this call: it is
     * returned unchanged only when no split was needed.</p>
     *
     * @return the bodies that now represent the edited structure, as a
     *         singleton list when nothing changed or one body per connected
     *         component after a split; empty when the body was removed
     */
    public List<PhysicsStructure> refreshStructure(PhysicsStructure structure, VoxelVolume volume) {
        if (closed) {
            return structure.closed() ? List.of() : List.of(structure);
        }
        // Project before replacing the region: the old region/chunks and the
        // current structure pose are still valid.
        projectSubLevelEntities();
        if (subLevel != null) {
            // Replacing the region destroys its block entities. Preserve their
            // live NBT so a door/button state change does not empty a chest or
            // reset a furnace in the same structure.
            final Map<net.minecraft.core.BlockPos, net.minecraft.nbt.CompoundTag> preserved =
                    subLevel.captureBlockEntities(structure);
            // Capture pending repeater/observer/fluid delays before the region
            // is removed. EmptyPhysicsLevel#addStructure cannot do this itself
            // on this path because the old region is already gone.
            final net.minecraft.nbt.CompoundTag preservedTicks =
                    subLevel.captureScheduledTicks(structure);
            try {
                subLevel.removeStructure(structure);
                subLevel.addStructure(structure, volume, preservedTicks);
                if (!preserved.isEmpty()) {
                    subLevel.applyBlockEntities(structure, preserved);
                }
                refreshStructureActors(structure);
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Could not refresh structure body #{} in the auxiliary void level",
                        structure.body().id(), t);
                structure.invalidateBlockEntityActors();
                structureActors.forget(structure);
            }
        } else {
            structure.invalidateBlockEntityActors();
            structureActors.forget(structure);
        }
        final List<PhysicsStructure> result =
                StructureSplitter.splitIfNeeded(this, structure, volume);
        attachedEntities.relocate(structure, result);
        return result;
    }

    /**
     * Re-discovers block-entity physics actors for a structure after its block
     * copy changed. The runtime state is dropped first so cached failure or
     * force-group state from the old actor set cannot leak into the new one.
     */
    private void refreshStructureActors(PhysicsStructure structure) {
        structureActors.forget(structure);
        if (subLevel == null) {
            structure.invalidateBlockEntityActors();
            return;
        }
        try {
            structure.setBlockEntityActors(subLevel.blockEntityActors(structure));
        } catch (Throwable t) {
            structure.invalidateBlockEntityActors();
            Crythst.LOGGER.warn("Could not discover block-entity actors for structure body #{}",
                    structure.body().id(), t);
        }
    }

    /**
     * Lazily creates the auxiliary void level.
     *
     * @return the level, or {@code null} when disabled or construction failed
     */
    private EmptyPhysicsLevel subLevel() {
        if (!config.subLevelEnabled || closed) {
            return null;
        }
        if (subLevel != null) {
            return subLevel;
        }
        if (subLevelCreationFailed) {
            return null;
        }
        subLevel = EmptyPhysicsLevel.create(level);
        if (subLevel == null) {
            subLevelCreationFailed = true;
        } else {
            subLevel.setAttachedEntities(attachedEntities);
            subLevel.setEntityProjector(this::projectVoidEntity);
            Crythst.LOGGER.info("Created auxiliary void level for {} (block logic ticking enabled)",
                    level.dimension().identifier());
        }
        return subLevel;
    }

    /**
     * Reads the live auxiliary block for a structure voxel without loading a
     * chunk. Returns {@code null} when the auxiliary level is disabled, the
     * structure has no copied region, or the chunk is currently unloaded.
     */
    public EmptyPhysicsLevel.@org.jetbrains.annotations.Nullable LiveBlock liveBlock(
            PhysicsStructure structure, int x, int y, int z) {
        return subLevel == null ? null : subLevel.liveBlock(structure, x, y, z);
    }

    /**
     * The auxiliary void level, or {@code null} when it is disabled or failed
     * to start.
     *
     * <p>Read-only access for views and diagnostics. Callers must use
     * structure-local coordinates and the per-structure
     * {@link EmptyPhysicsLevel#regionView(PhysicsStructure)} mapping; the
     * auxiliary coordinates are an implementation detail.</p>
     */
    public @org.jetbrains.annotations.Nullable EmptyPhysicsLevel auxiliaryLevel() {
        return subLevel;
    }

    /**
     * Host-world registry for decorative/rideable entities attached to a
     * structure. This is the integration point for the placement handler:
     * after spawning a real host item frame/painting/seat, call
     * {@link StructureAttachedEntities#attachAtCurrentPosition} (or
     * {@code attachRideable}/{@code attachDecoration}) with the structure.
     */
    public StructureAttachedEntities attachedEntities() {
        return attachedEntities;
    }

    /**
     * Updates all attached host mirrors and their passengers from the current
     * structure poses. Normally called by {@link #tick()}; exposed for tests
     * and integrations that drive physics manually.
     */
    public void tickAttachedEntities() {
        attachedEntities.tick();
    }

    /**
     * Starts projecting the auxiliary level's interaction sounds/particles to
     * the host world. See {@link EmptyPhysicsLevel#beginAudioProjection}.
     */
    public boolean beginAuxiliaryAudioProjection(PhysicsStructure structure) {
        return subLevel != null && subLevel.beginAudioProjection(structure);
    }

    /** Ends the projection started by {@link #beginAuxiliaryAudioProjection}. */
    public void endAuxiliaryAudioProjection() {
        if (subLevel != null) {
            subLevel.endAudioProjection();
        }
    }

    /**
     * Mirrors auxiliary block-state changes (button press, door open, ...) back
     * to the structure volume/side table. Non-loading and cheap when nothing
     * changed.
     */
    public void flushAuxiliaryBlockStates() {
        if (subLevel != null) {
            applyAuxiliaryEdits(subLevel.flushBlockStateChanges());
            // A use/break can spawn drops, XP orbs or TNT in the void.
            projectSubLevelEntities();
        }
    }

    /**
     * Rebuilds colliders after vanilla block logic changed a structure volume.
     * Runs once per changed structure per tick, not once per block.
     *
     * <p>Edits originate in the auxiliary copy, so the copy is already correct;
     * re-indexing in place is enough and deliberately avoids re-copying the
     * whole region (which would allocate a new region base on every piston or
     * door tick). Volume-bound changes made by the placement/expansion path
     * still use {@link #refreshStructure(PhysicsStructure, VoxelVolume)}.</p>
     */
    private void applyAuxiliaryEdits(List<PhysicsStructure> changed) {
        if (changed == null || changed.isEmpty()) {
            return;
        }
        for (PhysicsStructure structure : List.copyOf(changed)) {
            if (structure.closed() || !structures.contains(structure)) {
                continue;
            }
            try {
                final VoxelVolume volume = structure.volume();
                if (StructureSplitter.isMassless(volume)) {
                    StructureSplitter.removeIfMassless(this, structure, volume);
                    continue;
                }
                structure.reindex(volume);
                refreshStructureActors(structure);
                // Reconnect/split editing must still run; this may close the
                // original and create new bodies, each with its own region.
                final List<PhysicsStructure> result =
                        StructureSplitter.splitIfNeeded(this, structure, volume);
                attachedEntities.relocate(structure, result);
            } catch (Throwable t) {
                if (subLevel != null) {
                    subLevel.reportTickFailure("block edit reindex", t);
                }
            }
        }
    }

    /**
     * Ticks block logic once per call. Empty chunks and inactive structures are
     * never touched.
     */
    private void tickSubLevel() {
        if (!config.subLevelEnabled || subLevel == null) {
            return;
        }
        try {
            final EmptyPhysicsLevel.TickResult result =
                    subLevel.tick(config.subLevelRandomTickSpeed);
            lastVoidTick = result;
            applyAuxiliaryEdits(result.changedStructures());
            if (Boolean.getBoolean("crythst.void.logTicks")
                    && (++voidTickLogCounter % 100 == 0)) {
                Crythst.LOGGER.info("Crythst void status: {}", voidTickStatus());
            }
        } catch (Throwable t) {
            subLevel.reportTickFailure("tick", t);
        }
    }

    /**
     * Projects entities that vanilla block logic spawned in the auxiliary void
     * level into this host level.
     *
     * <p>Runs after the auxiliary level has ticked block entities (dispensers,
     * droppers, TNT ignition, ...). The region/chunk list of the auxiliary
     * level is the scan boundary, so this never loads a chunk. Failed
     * projections stay in the void level and are retried after a short
     * cooldown; they are also dropped when their structure region is removed.</p>
     */
    /**
     * Immediately projects entities sitting in active auxiliary regions. Used
     * by the void-operation redirector after vanilla break/use drops or spawns
     * an entity outside the normal tick boundary.
     */
    public void projectAuxiliaryEntities() {
        projectSubLevelEntities();
    }

    private void projectSubLevelEntities() {
        if (subLevel == null || closed) {
            return;
        }
        if (structures.isEmpty()) {
            lastEntityProjection = StructureEntityProjection.Batch.EMPTY;
            return;
        }
        lastEntityProjection = StructureEntityProjection.Batch.EMPTY;
        subLevel.collectRegionEntities(this::projectVoidEntity);
    }

    /**
     * Projects one entity out of a void region. Also used by the void-level
     * tick as its before/after-entity ejection callback.
     */
    private StructureEntityProjection.Result projectVoidEntity(
            EmptyPhysicsLevel.RegionView region, net.minecraft.world.entity.Entity entity) {
        final PhysicsStructure structure = structureById(region.structureId());
        if (structure == null || structure.closed() || entity == null) {
            return StructureEntityProjection.Result.SKIPPED_REMOVED;
        }
        if (entity.isRemoved()) {
            return StructureEntityProjection.Result.SKIPPED_REMOVED;
        }
        final UUID uuid = entity.getUUID();
        final long now = level.getGameTime();
        final Long retryAfter = entityProjectionRetryAfter.get(uuid);
        if (retryAfter != null && retryAfter > now) {
            return StructureEntityProjection.Result.FAILED;
        }
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(
                        region.localOriginX(), region.localOriginY(), region.localOriginZ());
        final StructureEntityProjection.Result result =
                StructureEntityProjection.project(level, structure, anchor, entity);
        lastEntityProjection = lastEntityProjection.plus(result);
        if (result == StructureEntityProjection.Result.FAILED) {
            entityProjectionRetryAfter.put(uuid, now + 20L);
            if (!entityProjectionFailureLogged) {
                entityProjectionFailureLogged = true;
                Crythst.LOGGER.warn("Could not project entity {} from the auxiliary void level "
                                + "for {}; it stays in the void region and will be retried",
                        entity.getType(), level.dimension().identifier());
            }
        } else {
            entityProjectionRetryAfter.remove(uuid);
        }
        if (result == StructureEntityProjection.Result.PROJECTED) {
            projectedEntityCount++;
        }
        return result;
    }

    private @org.jetbrains.annotations.Nullable PhysicsStructure structureById(long id) {
        for (PhysicsStructure structure : structures) {
            if (!structure.closed() && structure.body().id() == id) {
                return structure;
            }
        }
        return null;
    }

    public void tick() {
        if (closed) {
            return;
        }
        final long now = System.nanoTime();
        final double wallDelta = Math.max(0.0, (now - lastTickNanos) / 1_000_000_000.0);
        lastTickNanos = now;

        final TickRateManager tickRate = level.getServer().tickRateManager();
        final boolean frozen = tickRate.isFrozen() && !tickRate.isSteppingForward();
        final double simulationDelta;
        if (frozen) {
            simulationDelta = 0.0;
        } else if (config.serverTickTiming) {
            // Feed the fixed-step clock exactly one server tick of game time.
            // Wall-clock jitter between END_SERVER_TICK callbacks (burst ticks,
            // frame spikes, integrated-server scheduling) must not change how
            // many substeps a tick runs: the renderer interpolates the two
            // tick-boundary poses with Minecraft's tick partial tick, so
            // variable steps per tick are visible as trembling.
            simulationDelta = 1.0 / Math.max(1.0, tickRate.tickrate());
        } else {
            // Legacy wall-clock mode, retained for A/B testing and for servers
            // that deliberately want physics to catch up in real time.
            simulationDelta = Math.min(wallDelta, config.maxFrameSeconds);
        }

        if (!frozen) {
            // The terrain region is expanded by the velocity over the same
            // interval the physics clock is about to advance, so a fast body
            // cannot outrun the cache between refreshes.
            terrain.tick(simulationDelta);
            CcdMotionGuard.update(world, simulationDelta);
            PlayerGravity.setWorldDirection(level.dimension(), world.gravity());
        }
        final int steps = simulationDelta > 0.0 ? world.advance(simulationDelta) : 0;
        if (!frozen) {
            unstuck.tick();
        }

        // Publish exactly one pose per server tick. Do not push after each
        // substep: substeps are simulated in a burst, and Minecraft's render
        // alpha spans the tick, not the substep.
        double maxPoseDelta = 0.0;
        double maxPoseAngle = 0.0;
        for (PhysicsStructure structure : structures) {
            structure.syncRenderPose();
            maxPoseDelta = Math.max(maxPoseDelta, structure.renderPoseTranslationDelta());
            maxPoseAngle = Math.max(maxPoseAngle, structure.renderPoseAngleDelta());
        }
        renderDebugSnapshot = world.debugSnapshot();
        maybeRebase();
        // Attached host mirrors (item frames, paintings, seats) and their
        // passengers follow the pose that was just published. This runs before
        // the void block pass so placement/mount code sees the current pose.
        attachedEntities.tick();
        if (!frozen) {
            // Block logic advances with the simulated world. Ticking here means
            // one random-tick pass per server tick, not one per Bullet sub-step.
            tickSubLevel();
            // Dispensers/droppers spawn item, TNT and projectile entities inside
            // the auxiliary level. Move them into this host level immediately
            // after the block pass so they are visible and interactable.
            projectSubLevelEntities();
        }
        timing.record(wallDelta, simulationDelta, steps, world.interpolationAlpha(),
                maxPoseDelta, maxPoseAngle, config.serverTickTiming, frozen);
    }

    /**
     * Latest timing/interpolation diagnostic for {@code /crythst timing}.
     */
    public PhysicsTimingMonitor.Snapshot timing() {
        return timing.snapshot();
    }

    /**
     * One-line timing summary for commands and logs.
     */
    public String timingText() {
        return timing.snapshot().summary();
    }

    /**
     * Runs explicit fixed steps while the world is paused or for deterministic
     * tests.
     */
    public void step(int count) {
        final double fixedStep = world.settings().fixedTimeStep();
        terrain.ensureNow(fixedStep * Math.max(1, count));
        CcdMotionGuard.update(world, fixedStep * Math.max(1, count));
        for (int i = 0; i < count; i++) {
            world.step(fixedStep);
        }
        unstuck.force();
        double maxPoseDelta = 0.0;
        double maxPoseAngle = 0.0;
        for (PhysicsStructure structure : structures) {
            structure.syncRenderPose();
            maxPoseDelta = Math.max(maxPoseDelta, structure.renderPoseTranslationDelta());
            maxPoseAngle = Math.max(maxPoseAngle, structure.renderPoseAngleDelta());
        }
        renderDebugSnapshot = world.debugSnapshot();
        if (count > 0) {
            // A manual batch may advance many fixed steps at once. Tick the
            // void level once for the batch to avoid multiplying random ticks
            // by the number of fixed steps.
            tickSubLevel();
            timing.record(0.0, count * fixedStep, count, world.interpolationAlpha(),
                    maxPoseDelta, maxPoseAngle, false, paused());
        }
    }

    private void maybeRebase() {
        if (world.bodies().isEmpty()) {
            return;
        }
        final Vector3d center = new Vector3d();
        int count = 0;
        for (io.mikaple.crythst.physics.body.BodyHandle body : world.bodies()) {
            center.add(body.transform().position());
            count++;
        }
        if (count == 0) {
            return;
        }
        center.div(count);
        if (center.distanceSquared(world.origin()) > config.rebaseDistance * config.rebaseDistance) {
            Crythst.LOGGER.debug("Rebasing {} physics origin to {}", level.dimension().identifier(), center);
            world.rebaseOrigin(center);
        }
    }

    public boolean paused() {
        return world.paused();
    }

    public void setPaused(boolean paused) {
        world.setPaused(paused);
    }

    /**
     * Removes all structures, terrain sections and loose bodies. Intended for
     * test commands; gameplay mods should remove their own bodies individually.
     */
    public void clearAllBodies() {
        // Move any entities the regions spawned into this level before the
        // auxiliary regions disappear.
        projectSubLevelEntities();
        // Release attached host mirrors before the structures and regions
        // disappear, then clear the auxiliary regions exactly once before their
        // structures (and therefore their body ids) are closed.
        attachedEntities.clear();
        clearSubLevel();
        for (PhysicsStructure structure : structures) {
            structure.invalidateBlockEntityActors();
            structure.close();
        }
        structures.clear();
        structureActors.clear();
        refreshRenderStructures();
        terrain.invalidateAll();
        for (io.mikaple.crythst.physics.body.BodyHandle body
                : java.util.List.copyOf(world.bodies())) {
            world.removeBody(body);
        }
    }

    public void invalidateTerrain() {
        terrain.invalidateAll();
    }

    /**
     * Immutable terrain-collision diagnostics. The snapshot is published by the
     * server thread and is safe to read from the render thread (for example the
     * {@code crythst:physics_terrain} F3+F6 layer).
     *
     * <p>It contains cached section keys, their static body ids and merged
     * boxes, the number of exact partial-block boxes, the refresh reason/count
     * and one region entry per dynamic body with its AABB, motion-expanded AABB
     * and whether the current AABB is fully covered by cached terrain.</p>
     */
    public TerrainCollisionSnapshot terrainSnapshot() {
        return terrain.snapshot();
    }

    /**
     * Alias with an explicit render-thread name; the returned snapshot is
     * immutable and replaced atomically by the server thread.
     */
    public TerrainCollisionSnapshot renderTerrainSnapshot() {
        return terrain.snapshot();
    }

    /**
     * Loads terrain sections around current bodies immediately instead of
     * waiting for the throttled refresh. Useful after spawning a test body.
     */
    public void ensureTerrain() {
        terrain.ensureNow();
    }

    /** Immediately attempts to push bodies out of solid blocks. */
    public int forceUnstuck() {
        return unstuck.force();
    }

    /**
     * Reattaches saved block-entity NBT after a restored structure is created.
     * No-op when the auxiliary void level is disabled or unavailable.
     */
    public void applyBlockEntities(PhysicsStructure structure,
                                   java.util.Map<net.minecraft.core.BlockPos, net.minecraft.nbt.CompoundTag> data) {
        if (subLevel != null) {
            subLevel.applyBlockEntities(structure, data);
            // Restored NBT can turn a plain copied block into an actor; refresh
            // the cached list so the next substep sees it.
            refreshStructureActors(structure);
        }
    }

    /**
     * Captures pending block/fluid scheduled ticks for persistence. No-op when
     * the auxiliary void level is unavailable.
     */
    public net.minecraft.nbt.CompoundTag captureScheduledTicks(PhysicsStructure structure) {
        return subLevel == null
                ? new net.minecraft.nbt.CompoundTag()
                : subLevel.captureScheduledTicks(structure);
    }

    /** Re-schedules ticks captured by {@link #captureScheduledTicks}. */
    public void applyScheduledTicks(PhysicsStructure structure,
                                    net.minecraft.nbt.CompoundTag ticks) {
        if (subLevel != null) {
            subLevel.applyScheduledTicks(structure, ticks);
        }
    }

    public void invalidateTerrainAt(net.minecraft.core.BlockPos pos) {
        terrain.invalidateAt(pos);
    }

    public int terrainSectionCount() {
        return terrain.sectionCount();
    }

    /**
     * Number of auxiliary void chunks currently receiving block ticks.
     */
    public int subLevelChunkCount() {
        return subLevel == null ? 0 : subLevel.activeChunkCount();
    }

    /**
     * Number of structures currently copied into the auxiliary void level.
     */
    public int subLevelRegionCount() {
        return subLevel == null ? 0 : subLevel.regionCount();
    }

    /** Cumulative number of ordinary entities ejected from the void level. */
    public long auxiliaryLevelEjectedEntities() {
        return subLevel == null ? 0 : subLevel.totalEjectedEntities();
    }

    /**
     * Entities spawned by block logic in the auxiliary level during the most
     * recent projection pass, plus skip/failure counts.
     */
    public StructureEntityProjection.Batch lastEntityProjection() {
        return lastEntityProjection;
    }

    /**
     * Total entities projected from the auxiliary level into this host level
     * since this physics world was created.
     */
    public long projectedEntityCount() {
        return projectedEntityCount;
    }

    /**
     * Entities currently waiting in auxiliary structure regions. This should
     * normally be zero; a non-zero value means a projection is failing or a
     * block-attached/plot-only entity was deliberately left behind.
     */
    public int subLevelEntityCount() {
        return subLevel == null ? 0 : subLevel.regionEntityCount();
    }

    /** One-line projection diagnostic for commands and logs. */
    public String entityProjectionStatus() {
        return lastEntityProjection.summary()
                + " totalProjected=" + projectedEntityCount
                + " pendingInVoid=" + subLevelEntityCount();
    }

    /**
     * Result of the most recent auxiliary block-logic tick: scheduled block and
     * fluid ticks, block events, random ticks and mirrored edits.
     */
    public EmptyPhysicsLevel.TickResult lastVoidTick() {
        return lastVoidTick;
    }

    /** One-line world-logic diagnostic for commands and logs. */
    public String voidTickStatus() {
        if (subLevel == null) {
            return "voidLevel=disabled"
                    + " attached=" + attachedEntities.attachedCount()
                    + " attachedFailures=" + attachedEntities.failureCount();
        }
        return "voidLevel=active"
                + " regions=" + subLevel.regionCount()
                + " activeChunks=" + subLevel.activeChunkCount()
                + " blockTicks=" + subLevel.totalBlockTicks()
                + " fluidTicks=" + subLevel.totalFluidTicks()
                + " pendingBlockTicks=" + subLevel.pendingBlockTicks()
                + " pendingFluidTicks=" + subLevel.pendingFluidTicks()
                + " blockEvents=" + subLevel.totalBlockEvents()
                + " randomTicks=" + subLevel.totalRandomTicks()
                + " mirroredBlocks=" + subLevel.totalMirroredBlocks()
                + " skippedBlocks=" + subLevel.totalSkippedBlocks()
                + " neighbors=" + subLevel.totalNeighborUpdates()
                + " droppedWork=" + subLevel.totalDroppedWork()
                + " entitiesEjected=" + subLevel.totalEjectedEntities()
                + " attachedAdopted=" + subLevel.totalAdoptedAttachedEntities()
                + " ejectionFailures=" + subLevel.totalEjectionFailures()
                + " attached=" + attachedEntities.summary()
                + " " + subLevel.tickDiagnostics()
                + " last=" + lastVoidTick.summary();
    }

    /**
     * Number of block-entity physics actors across all structures.
     *
     * <p>Cheap diagnostic: reads the cached per-structure actor lists without
     * touching the auxiliary level or loading chunks. Zero when the auxiliary
     * level is disabled or no block entity implements
     * {@link io.mikaple.crythst.api.block.BlockEntityStructureActor}.</p>
     *
     * @return total cached actor count
     */
    public int structureActorCount() {
        int count = 0;
        for (PhysicsStructure structure : structures) {
            count += structure.blockEntityActors().size();
        }
        return count;
    }

    /**
     * Number of structures that currently have at least one cached physics
     * actor. Diagnostic only.
     *
     * @return structures with actors
     */
    public int structuresWithActors() {
        int count = 0;
        for (PhysicsStructure structure : structures) {
            if (!structure.blockEntityActors().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * One-line actor cache summary for commands and logs.
     *
     * @return a human-readable count summary
     */
    public String structureActorStatus() {
        return structureActorCount() + " actor(s) on " + structuresWithActors()
                + " structure(s); runtime states=" + structureActors.cachedStructureCount()
                + ", force groups=" + structureActors.cachedForceGroupCount();
    }

    /**
     * Whether the auxiliary void level exists (it is created on the first
     * structure).
     */
    public boolean subLevelActive() {
        return subLevel != null && !subLevelCreationFailed;
    }

    private void clearSubLevel() {
        if (subLevel == null) {
            return;
        }
        try {
            subLevel.clear();
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Error clearing the auxiliary void level for {}",
                    level.dimension().identifier(), t);
        }
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        attachedEntities.clear();
        if (subLevel != null) {
            try {
                subLevel.close();
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Error closing the auxiliary void level for {}",
                        level.dimension().identifier(), t);
            }
            subLevel = null;
        }
        for (PhysicsStructure structure : structures) {
            structure.invalidateBlockEntityActors();
            structure.close();
        }
        structures.clear();
        structureActors.clear();
        refreshRenderStructures();
        renderDebugSnapshot = null;
        terrain.close();
        world.close();
        Crythst.LOGGER.info("Closed physics world for {}", level.dimension().identifier());
    }
}
