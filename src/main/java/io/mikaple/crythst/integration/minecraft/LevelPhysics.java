package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.tick.PhysicsTimingMonitor;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Auxiliary void level for block logic. Created lazily on the first
     * structure so dimensions without physics structures never allocate a
     * level or load chunks.
     */
    private EmptyPhysicsLevel subLevel;
    private boolean subLevelCreationFailed;
    private boolean subLevelTickFailureLogged;

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
        PlayerGravity.setWorldDirection(level.dimension(), world.gravity());
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

    public PhysicsStructure createStructure(StructureDef definition) {
        final PhysicsStructure structure = new PhysicsStructure(world, definition);
        structures.add(structure);
        refreshRenderStructures();
        terrain.ensureNow();
        final EmptyPhysicsLevel sub = subLevel();
        if (sub != null) {
            try {
                sub.addStructure(structure, definition.volume());
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
        return structure;
    }

    public void removeStructure(PhysicsStructure structure) {
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
     * <p>Blocks that no longer form one six-connected component become
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
        if (subLevel != null) {
            try {
                subLevel.removeStructure(structure);
                subLevel.addStructure(structure, volume);
            } catch (Throwable t) {
                Crythst.LOGGER.warn("Could not refresh structure body #{} in the auxiliary void level",
                        structure.body().id(), t);
            }
        }
        return StructureSplitter.splitIfNeeded(this, structure, volume);
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
            Crythst.LOGGER.info("Created auxiliary void level for {} (block logic ticking enabled)",
                    level.dimension().identifier());
        }
        return subLevel;
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
            subLevel.tick(config.subLevelRandomTickSpeed);
        } catch (Throwable t) {
            if (!subLevelTickFailureLogged) {
                subLevelTickFailureLogged = true;
                Crythst.LOGGER.warn("Error ticking the auxiliary void level for {}; "
                        + "block logic may be incomplete", level.dimension().identifier(), t);
            }
        }
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
            terrain.tick();
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
        if (!frozen) {
            // Block logic advances with the simulated world. Ticking here means
            // one random-tick pass per server tick, not one per Bullet sub-step.
            tickSubLevel();
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
        terrain.ensureNow();
        final double fixedStep = world.settings().fixedTimeStep();
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
        // Clear the auxiliary regions exactly once, before their structures
        // (and therefore their body ids) are closed.
        clearSubLevel();
        for (PhysicsStructure structure : structures) {
            structure.close();
        }
        structures.clear();
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
            structure.close();
        }
        structures.clear();
        refreshRenderStructures();
        renderDebugSnapshot = null;
        terrain.close();
        world.close();
        Crythst.LOGGER.info("Closed physics world for {}", level.dimension().identifier());
    }
}
