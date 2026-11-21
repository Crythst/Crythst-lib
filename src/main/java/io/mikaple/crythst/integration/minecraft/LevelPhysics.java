package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.server.level.ServerLevel;
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
    public void refreshStructure(PhysicsStructure structure) {
        refreshStructure(structure, structure.volume());
    }

    /**
     * Re-copies a structure's blocks after a volume change.
     */
    public void refreshStructure(PhysicsStructure structure, VoxelVolume volume) {
        if (subLevel == null || closed) {
            return;
        }
        try {
            subLevel.removeStructure(structure);
            subLevel.addStructure(structure, volume);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Could not refresh structure body #{} in the auxiliary void level",
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
        double delta = (now - lastTickNanos) / 1_000_000_000.0;
        lastTickNanos = now;
        if (delta <= 0.0) {
            return;
        }
        delta = Math.min(delta, config.maxFrameSeconds);
        terrain.tick();
        PlayerGravity.setWorldDirection(level.dimension(), world.gravity());
        world.advance(delta);
        unstuck.tick();
        for (PhysicsStructure structure : structures) {
            structure.syncRenderPose();
        }
        if (config.debug) {
            renderDebugSnapshot = world.debugSnapshot();
        }
        maybeRebase();
        // Block logic advances with the simulated world. Ticking here means one
        // random-tick pass per server tick, not one per Bullet sub-step.
        tickSubLevel();
    }

    /**
     * Runs explicit fixed steps while the world is paused or for deterministic
     * tests.
     */
    public void step(int count) {
        terrain.ensureNow();
        for (int i = 0; i < count; i++) {
            world.step(world.settings().fixedTimeStep());
        }
        unstuck.force();
        for (PhysicsStructure structure : structures) {
            structure.syncRenderPose();
        }
        if (config.debug) {
            renderDebugSnapshot = world.debugSnapshot();
        }
        if (count > 0) {
            // A manual batch may advance many fixed steps at once. Tick the
            // void level once for the batch to avoid multiplying random ticks
            // by the number of fixed steps.
            tickSubLevel();
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
