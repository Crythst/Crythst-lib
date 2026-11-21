package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of the physics structures attached to one level.
 *
 * <p>The container is the stable lookup surface above {@link LevelPhysics}.
 * Server containers are authoritative and must be queried on the server thread.
 * Client containers are populated from the client-side render/network sources;
 * they never touch server physics state.</p>
 *
 * <p>The list returned by {@link #structures()} is an immutable snapshot. Use
 * {@link PhysicsStructure#pose()} or {@link PhysicsStructure#renderState(double)}
 * when only a render-safe pose is needed.</p>
 */
public interface StructureContainer {

    /**
     * Level this container belongs to.
     */
    Level level();

    /**
     * Structures currently attached to this container, as an immutable snapshot.
     */
    List<PhysicsStructure> structures();

    /**
     * Returns the structure with the given physics body id, or {@code null}.
     */
    default @Nullable PhysicsStructure byId(long bodyId) {
        for (PhysicsStructure structure : structures()) {
            if (!structure.closed() && structure.body().id() == bodyId) {
                return structure;
            }
        }
        return null;
    }

    /**
     * Returns a level-like block view over the structure with the given body
     * id, or {@code null} when no such structure is visible.
     *
     * <p>Server containers return an authoritative writable view. Client
     * containers return a read-only view over the integrated-client render
     * structure or the synced network snapshot. The returned view is not
     * stable across edits, reindexing, splits or removal; reacquire it when
     * {@link io.mikaple.crythst.api.sublevel.view.StructureLevelView#isCurrent()}
     * becomes {@code false}. See
     * {@link io.mikaple.crythst.api.sublevel.view.StructureLevelViews}.</p>
     */
    default @Nullable io.mikaple.crythst.api.sublevel.view.StructureLevelView view(long bodyId) {
        return io.mikaple.crythst.api.sublevel.view.StructureLevelViews.of(this, bodyId);
    }

    /**
     * Resolves a structure by a display label or by its decimal body id.
     *
     * <p>Crythst structures do not currently carry a dedicated name field. A
     * label is therefore only usable when the structure's body user data is a
     * non-structure object whose string form matches; otherwise callers should
     * use {@link #byId(long)}. The decimal body id is always accepted.</p>
     */
    default @Nullable PhysicsStructure byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (PhysicsStructure structure : structures()) {
            if (structure.closed()) {
                continue;
            }
            if (name.equals(Long.toString(structure.body().id()))) {
                return structure;
            }
            final Object data = structure.body().userData();
            if (data != null && data != structure && name.equals(String.valueOf(data))) {
                return structure;
            }
        }
        return null;
    }

    /**
     * Finds the structure whose solid volume contains a world-space point.
     */
    default @Nullable PhysicsStructure containing(Vec3 point) {
        if (point == null) {
            return null;
        }
        final Vector3d query = new Vector3d(point.x, point.y, point.z);
        for (PhysicsStructure structure : structures()) {
            if (structure.closed() || !structure.obb().contains(query)) {
                continue;
            }
            final Vector3d voxel = structure.worldToVoxel(new Vector3d(), query);
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
     * Returns structures whose world AABB overlaps {@code box}.
     */
    default List<PhysicsStructure> intersecting(Aabb box) {
        if (box == null) {
            throw new NullPointerException("box");
        }
        final List<PhysicsStructure> result = new ArrayList<>();
        for (PhysicsStructure structure : structures()) {
            if (!structure.closed() && structure.aabb().intersects(box)) {
                result.add(structure);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Number of structures in {@link #structures()}.
     */
    default int size() {
        return structures().size();
    }

    /**
     * Whether this container currently has no structures.
     */
    default boolean isEmpty() {
        return structures().isEmpty();
    }

    /**
     * Returns the existing container for a level, or {@code null} when that
     * level has no physics world yet.
     *
     * <p>On a dedicated server only server levels are passed here; client
     * classes are reached through a lazy holder and are never loaded on a
     * dedicated server. Creating a physics world (for example through
     * {@code CrythstApi.physics(ServerLevel)}) also creates its container and
     * fires {@link StructureContainerEvents}.</p>
     */
    static @Nullable StructureContainer getContainer(Level level) {
        if (level == null) {
            return null;
        }
        if (level instanceof ServerLevel serverLevel) {
            return getContainer(serverLevel);
        }
        if (!level.isClientSide()) {
            return null;
        }
        return StructureContainerClientHolder.get(level);
    }

    /**
     * Returns the server container for an existing physics world, or
     * {@code null} when the level has not been initialised yet.
     */
    static @Nullable ServerStructureContainer getContainer(ServerLevel level) {
        if (level == null) {
            return null;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        return physics == null ? null : ServerStructureContainer.of(physics);
    }
}
