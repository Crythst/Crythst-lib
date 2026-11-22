package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.api.sublevel.view.StructureLevelViews;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.structure.view.ServerStructureLevelView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side {@link StructurePathSource}.
 *
 * <p>The snapshot is rebuilt when the game tick, structure list, structure
 * content epoch or a structure pose changes. That makes a path region built at
 * the beginning of a path computation invalidate its own index if a structure
 * moves or is edited before the computation finishes.</p>
 *
 * <p>Views are deliberately created without the auxiliary ticking level
 * ({@code physics == null} in the view constructor). Pathfinding reads the
 * authoritative structure volume / block-state side table directly, which
 * never loads a chunk. The auxiliary level remains the block-logic backend; it
 * is not a prerequisite for AI visibility.</p>
 */
final class ServerStructurePathSource implements StructurePathSource {
    private static final Map<LevelPhysics, Snapshot> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final LevelPhysics physics;

    ServerStructurePathSource(LevelPhysics physics) {
        if (physics == null) {
            throw new NullPointerException("physics");
        }
        this.physics = physics;
    }

    @Override
    public List<StructurePathVolume> snapshot(Aabb query) {
        if (query == null) {
            throw new NullPointerException("query");
        }
        final Snapshot snapshot = current();
        if (snapshot.entries().isEmpty()) {
            return List.of();
        }
        final List<StructurePathVolume> found = new ArrayList<>(4);
        for (Entry entry : snapshot.entries()) {
            if (!entry.broadBounds().intersects(query)) {
                continue;
            }
            try {
                // Build the volume only for structures that can touch this
                // region. Building every structure in the dimension for every
                // mob path would be wasteful.
                found.add(StructurePathVolume.of(new ServerStructureLevelView(
                        entry.structure(), physics.level(), null)));
            } catch (RuntimeException ignored) {
                // Structure closed while building the path snapshot.
            }
        }
        return found;
    }

    @Override
    public long stamp() {
        return stampOf(physics);
    }

    @Override
    public String side() {
        return "server";
    }

    private Snapshot current() {
        final long stamp = stampOf(physics);
        final Snapshot cached = CACHE.get(physics);
        if (cached != null && cached.stamp() == stamp) {
            return cached;
        }
        final List<Entry> entries = new ArrayList<>();
        for (PhysicsStructure structure : physics.structures()) {
            if (structure == null || structure.closed()) {
                continue;
            }
            try {
                // Full volume bounds (not just colliding cells) so a structure
                // whose relevant blocks are water/lava/fire is still considered.
                entries.add(new Entry(structure, broadBounds(structure)));
            } catch (RuntimeException ignored) {
                // A closing structure can disappear between the list snapshot
                // and the bounds read. Skip it, don't break the path.
            }
        }
        final Snapshot rebuilt = new Snapshot(stamp, List.copyOf(entries));
        CACHE.put(physics, rebuilt);
        return rebuilt;
    }

    private static Aabb broadBounds(PhysicsStructure structure) {
        final IntBounds3 bounds = structure.volume().bounds();
        final double scale = structure.blockScale();
        final Transform3d pose = structure.pose();
        final double minX = bounds.minX() * scale;
        final double minY = bounds.minY() * scale;
        final double minZ = bounds.minZ() * scale;
        final double maxX = bounds.maxX() * scale;
        final double maxY = bounds.maxY() * scale;
        final double maxZ = bounds.maxZ() * scale;
        double worldMinX = Double.POSITIVE_INFINITY;
        double worldMinY = Double.POSITIVE_INFINITY;
        double worldMinZ = Double.POSITIVE_INFINITY;
        double worldMaxX = Double.NEGATIVE_INFINITY;
        double worldMaxY = Double.NEGATIVE_INFINITY;
        double worldMaxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d world = pose.transformPoint(new Vector3d(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ));
            worldMinX = Math.min(worldMinX, world.x);
            worldMinY = Math.min(worldMinY, world.y);
            worldMinZ = Math.min(worldMinZ, world.z);
            worldMaxX = Math.max(worldMaxX, world.x);
            worldMaxY = Math.max(worldMaxY, world.y);
            worldMaxZ = Math.max(worldMaxZ, world.z);
        }
        return new Aabb(new Vector3d(worldMinX, worldMinY, worldMinZ),
                new Vector3d(worldMaxX, worldMaxY, worldMaxZ));
    }

    private static long stampOf(LevelPhysics physics) {
        long stamp = 17L;
        stamp = stamp * 31L + physics.level().getGameTime();
        final List<PhysicsStructure> structures = physics.structures();
        stamp = stamp * 31L + structures.size();
        for (PhysicsStructure structure : structures) {
            if (structure == null || structure.closed()) {
                continue;
            }
            stamp = stamp * 31L + structure.body().id();
            stamp = stamp * 31L + StructureLevelViews.epoch(structure);
            stamp = stamp * 31L + structure.contentRevision();
            final Transform3d pose = structure.pose();
            stamp = hash(stamp, pose.position());
            stamp = hash(stamp, pose.rotation());
        }
        return stamp;
    }

    private static long hash(long seed, Vector3dc vector) {
        long hash = seed;
        hash = hash * 31L + Double.doubleToLongBits(vector.x());
        hash = hash * 31L + Double.doubleToLongBits(vector.y());
        hash = hash * 31L + Double.doubleToLongBits(vector.z());
        return hash;
    }

    private static long hash(long seed, Quaterniondc rotation) {
        long hash = seed;
        hash = hash * 31L + Double.doubleToLongBits(rotation.x());
        hash = hash * 31L + Double.doubleToLongBits(rotation.y());
        hash = hash * 31L + Double.doubleToLongBits(rotation.z());
        hash = hash * 31L + Double.doubleToLongBits(rotation.w());
        return hash;
    }

    private record Entry(PhysicsStructure structure, Aabb broadBounds) {
    }

    private record Snapshot(long stamp, List<Entry> entries) {
    }
}
