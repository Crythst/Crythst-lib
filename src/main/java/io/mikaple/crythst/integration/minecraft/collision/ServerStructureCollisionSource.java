package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Server-side structure source backed by the authoritative physics world.
 *
 * <p>The snapshot is rebuilt when the level's game time changes. Entity
 * movement runs during the server tick, before the physics tick for that same
 * tick, so a per-tick snapshot exactly matches the pose every entity sees. A
 * split or reindex replaces the structure list, which is visible on the next
 * tick; holding a snapshot for a single tick also avoids walking
 * {@code structures()} once per entity.</p>
 *
 * <p>All accessors are read-only. The collider list is immutable and shared
 * with the physics body.</p>
 */
public final class ServerStructureCollisionSource {
    private static final double SERVER_MAX_CORRECTION = 4.0;
    private static final java.util.Map<LevelPhysics, Snapshot> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ServerStructureCollisionSource() {
    }

    /**
     * Returns the source for this physics world, rebuilding it if the tick
     * changed or the structure list was replaced.
     */
    public static StructureCollisionSource of(LevelPhysics physics) {
        if (physics == null || physics.closed()) {
            return EmptySource.INSTANCE;
        }
        final long gameTime = physics.level().getGameTime();
        final int structureCount = physics.structures().size();
        final Snapshot cached = CACHE.get(physics);
        if (cached != null && cached.gameTime() == gameTime
                && cached.structureCount() == structureCount) {
            return cached;
        }
        final Snapshot rebuilt = snapshot(physics, gameTime, structureCount);
        CACHE.put(physics, rebuilt);
        return rebuilt;
    }

    /** Drops the cached snapshot for a world that is unloading. */
    public static void invalidate(LevelPhysics physics) {
        if (physics != null) {
            CACHE.remove(physics);
        }
    }

    private static Snapshot snapshot(LevelPhysics physics, long gameTime, int structureCount) {
        final List<PhysicsStructure> structures = physics.structures();
        if (structures.isEmpty()) {
            return new Snapshot(gameTime, structureCount, List.of());
        }
        final List<StructureCollisionBox> boxes = new ArrayList<>(structures.size());
        for (PhysicsStructure structure : structures) {
            if (structure.closed()) {
                continue;
            }
            final VoxelColliderData collider = structure.collider();
            if (collider.isEmpty()) {
                continue;
            }
            boxes.add(new StructureCollisionBox(
                    structure.body().id(),
                    structure.pose(),
                    collider.boxes(),
                    structure.blockScale(),
                    structure.aabb(),
                    collider.customParts()
            ));
        }
        return new Snapshot(gameTime, structureCount, List.copyOf(boxes));
    }

    private record Snapshot(long gameTime, int structureCount,
                            List<StructureCollisionBox> structures)
            implements StructureCollisionSource {
        @Override
        public List<StructureCollisionBox> candidates(Aabb query) {
            if (structures.isEmpty()) {
                return List.of();
            }
            final List<StructureCollisionBox> found = new ArrayList<>(4);
            for (StructureCollisionBox structure : structures) {
                if (structure.worldBounds().intersects(query)) {
                    found.add(structure);
                }
            }
            return found;
        }

        @Override
        public double maxCorrection() {
            return SERVER_MAX_CORRECTION;
        }

        @Override
        public String side() {
            return "server";
        }
    }

    /** Source used while a level has no physics world. */
    private enum EmptySource implements StructureCollisionSource {
        INSTANCE;

        @Override
        public List<StructureCollisionBox> candidates(Aabb query) {
            return List.of();
        }

        @Override
        public double maxCorrection() {
            return 0.0;
        }

        @Override
        public String side() {
            return "server";
        }
    }
}
