package io.mikaple.crythst.api;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.api.sublevel.ServerStructureContainer;
import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.api.sublevel.ticket.StructureLoadingTickets;
import io.mikaple.crythst.api.sublevel.tracking.StructureTrackingPoints;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.PhysicsException;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Public entry point for mods embedding Crythst.
 *
 * <p>The facade keeps callers away from the physics backend and gives both
 * sides a small, stable surface. Structure/body queries are authoritative only
 * on the server; on a client level {@link #structures(Level)} is empty and the
 * lookup methods return {@code null}. Server methods must be called on the
 * server thread.</p>
 */
public final class CrythstApi {
    private static volatile PhysicsEngine sharedEngine;

    private CrythstApi() {
    }

    /**
     * Returns the process-wide libbulletjme engine, creating it on first use.
     */
    public static PhysicsEngine engine() {
        PhysicsEngine local = sharedEngine;
        if (local == null) {
            synchronized (CrythstApi.class) {
                local = sharedEngine;
                if (local == null) {
                    local = BulletPhysicsEngine.create();
                    sharedEngine = local;
                }
            }
        }
        return local;
    }

    /**
     * Tests whether the native physics backend can be loaded.
     */
    public static boolean isAvailable() {
        try {
            engine();
            return true;
        } catch (PhysicsException | LinkageError e) {
            return false;
        }
    }

    /**
     * Creates a standalone physics world from explicit settings.
     *
     * <p>Most Minecraft mods should use {@link #physics(ServerLevel)} instead;
     * this method is for simulations that are not attached to a level.</p>
     */
    public static PhysicsWorld createWorld(WorldSettings settings) {
        return engine().createWorld(settings);
    }

    /**
     * Creates a standalone physics world with default settings.
     */
    public static PhysicsWorld createWorld() {
        return createWorld(WorldSettings.defaults());
    }

    /**
     * Returns a human-readable backend name and version.
     */
    public static String backendDescription() {
        final PhysicsEngine engine = engine();
        return engine.name() + " " + engine.version();
    }

    /**
     * Returns the live structures for a level.
     *
     * <p>On a client-only level this is empty. The returned list is an immutable
     * snapshot; use {@link PhysicsStructure#pose()} for a render-thread-safe
     * pose and never call native body methods from the render thread.</p>
     */
    public static List<PhysicsStructure> structures(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
        return physics == null ? List.of() : physics.structures();
    }

    /**
     * Returns the structure container for a level, or {@code null} when the
     * level has no physics world yet.
     *
     * <p>Server containers are authoritative. Client containers are read-only
     * views over the integrated-server render snapshots (singleplayer) and the
     * synced structure cache (multiplayer).</p>
     */
    public static @Nullable StructureContainer container(Level level) {
        return StructureContainer.getContainer(level);
    }

    /**
     * Returns the server structure container for a level, or {@code null} when
     * the level's physics world has not been created yet.
     *
     * <p>Use {@link #physics(ServerLevel)} first when a world should be created
     * on demand.</p>
     */
    public static @Nullable ServerStructureContainer container(ServerLevel level) {
        return StructureContainer.getContainer(level);
    }

    /**
     * Returns the level-like view for one structure body in a container.
     */
    public static @Nullable io.mikaple.crythst.api.sublevel.view.StructureLevelView view(
            StructureContainer container, long bodyId) {
        return container == null ? null : container.view(bodyId);
    }

    /**
     * Returns the level-like view for a live server structure.
     */
    public static @Nullable io.mikaple.crythst.api.sublevel.view.StructureLevelView view(
            PhysicsStructure structure) {
        return io.mikaple.crythst.api.sublevel.view.StructureLevelViews.of(structure);
    }

    /**
     * Acquires a host-chunk loading ticket for a structure.
     *
     * @param level        server level owning the structure
     * @param structure    live structure
     * @param radiusChunks extra chunk radius around the structure AABB
     * @return the ticket handle; release it when no longer needed
     */
    public static StructureLoadingTickets.StructureLoadingTicket acquireLoadingTicket(
            ServerLevel level, PhysicsStructure structure, int radiusChunks) {
        return StructureLoadingTickets.acquire(level, structure, radiusChunks);
    }

    /**
     * Persistent tracking points for a server level.
     */
    public static StructureTrackingPoints trackingPoints(ServerLevel level) {
        return StructureTrackingPoints.of(level);
    }

    /**
     * Returns structures whose world AABB overlaps {@code box}.
     */
    public static List<PhysicsStructure> structuresIn(Level level, Aabb box) {
        if (box == null) {
            throw new NullPointerException("box");
        }
        final List<PhysicsStructure> result = new ArrayList<>();
        for (PhysicsStructure structure : structures(level)) {
            if (!structure.closed() && structure.body().aabb().intersects(box)) {
                result.add(structure);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Minecraft-vector overload of {@link #structuresIn(Level, Aabb)}.
     */
    public static List<PhysicsStructure> structuresIn(Level level, net.minecraft.world.phys.AABB box) {
        if (box == null) {
            throw new NullPointerException("box");
        }
        return structuresIn(level, new Aabb(
                new Vector3d(box.minX, box.minY, box.minZ),
                new Vector3d(box.maxX, box.maxY, box.maxZ)));
    }

    /**
     * Finds the structure whose solid volume contains a world-space point.
     */
    public static @Nullable PhysicsStructure structureAt(Level level, Vec3 point) {
        if (point == null) {
            return null;
        }
        return structureAt(level, new Vector3d(point.x, point.y, point.z));
    }

    /**
     * Finds the structure whose solid volume contains a world-space point.
     */
    public static @Nullable PhysicsStructure structureAt(Level level, Vector3dc point) {
        if (point == null) {
            return null;
        }
        for (PhysicsStructure structure : structures(level)) {
            if (structure.closed() || !structure.obb().contains(point)) {
                continue;
            }
            final Vector3d voxel = structure.worldToVoxel(new Vector3d(), point);
            final int x = (int) Math.floor(voxel.x + 0.5);
            final int y = (int) Math.floor(voxel.y + 0.5);
            final int z = (int) Math.floor(voxel.z + 0.5);
            if (structure.volume().bounds().contains(x, y, z)
                    && structure.volume().isSolid(x, y, z)) {
                return structure;
            }
        }
        return null;
    }

    /**
     * Alias for {@link #structureAt(Level, Vec3)}.
     */
    public static @Nullable PhysicsStructure structureContaining(Level level, Vec3 point) {
        return structureAt(level, point);
    }

    /**
     * Returns the API pipeline for a server level, creating the level's physics
     * world on first use.
     *
     * @return the pipeline, or {@code null} when the physics backend is
     *         unavailable or world creation failed
     */
    public static @Nullable PhysicsPipeline physics(ServerLevel level) {
        if (level == null || !MinecraftPhysics.available()) {
            return null;
        }
        try {
            LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics == null) {
                physics = MinecraftPhysics.get(level);
            }
            return new PhysicsPipeline(physics);
        } catch (RuntimeException | LinkageError failure) {
            Crythst.LOGGER.warn("Could not obtain a physics pipeline for {}", level.dimension(), failure);
            return null;
        }
    }

    /**
     * Finds a body in a level's existing physics world without creating one.
     *
     * @return a handle, or {@code null} when the level has no physics world or
     *         the id is unknown
     */
    public static @Nullable RigidBodyHandle body(Level level, long bodyId) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
        return physics == null ? null : new PhysicsPipeline(physics).body(bodyId);
    }
}
