package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureContainerQueryTest {

    @Test
    void queriesByIdPositionAndIntersection() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure first = staticStructure(world, new Vector3d(10.0, 0.0, 0.0));
            final PhysicsStructure second = staticStructure(world, new Vector3d(30.0, 0.0, 0.0));
            final TestContainer container = new TestContainer(List.of(first, second));

            assertSame(first, container.byId(first.body().id()));
            assertSame(second, container.byId(second.body().id()));
            assertNull(container.byId(first.body().id() + second.body().id() + 999_999L));
            assertSame(first, container.byName(Long.toString(first.body().id())));
            assertNull(container.byName("not-a-structure"));

            final Vector3d center = first.blockCenter(1, 1, 1);
            assertSame(first, container.containing(new Vec3(center.x, center.y, center.z)));
            assertNull(container.containing(new Vec3(center.x + 100.0, center.y, center.z)));

            final Aabb aroundFirst = Aabb.fromCenterSize(first.aabb().center(), new Vector3d(0.5, 0.5, 0.5));
            assertEquals(List.of(first), container.intersecting(aroundFirst));
            final Aabb farAway = Aabb.fromCenterSize(new Vector3d(1_000.0, 0.0, 0.0),
                    new Vector3d(1.0, 1.0, 1.0));
            assertTrue(container.intersecting(farAway).isEmpty());

            assertEquals(2, container.size());
            assertFalse(container.isEmpty());
            assertTrue(new TestContainer(List.of()).isEmpty());

            first.close();
            second.close();
        }
    }

    @Test
    void observerDiffReportsRemovedAndAddedBodiesOnly() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure first = staticStructure(world, new Vector3d(0.0, 0.0, 0.0));
            final PhysicsStructure second = staticStructure(world, new Vector3d(20.0, 0.0, 0.0));
            final PhysicsStructure third = staticStructure(world, new Vector3d(40.0, 0.0, 0.0));
            final RecordingObserver observer = new RecordingObserver();

            StructureObserverSupport.diff(List.of(first, second), List.of(second, third), observer);

            assertEquals(List.of(first), observer.removed);
            assertEquals(List.of(third), observer.added);
            assertTrue(observer.refreshed.isEmpty());

            observer.clear();
            StructureObserverSupport.diff(List.of(second), List.of(second), observer);
            assertTrue(observer.added.isEmpty());
            assertTrue(observer.removed.isEmpty());

            first.close();
            second.close();
            third.close();
        }
    }

    private static PhysicsStructure staticStructure(PhysicsWorld world, Vector3d position) {
        final MutableVoxelVolume volume = VoxelVolumes.filled(
                new IntBounds3(0, 0, 0, 2, 2, 2), 1000.0, PhysicsMaterial.STONE);
        return new PhysicsStructure(world, StructureDef.builder(volume)
                .type(BodyType.STATIC)
                .pose(Transform3d.of(position))
                .build());
    }

    private static final class RecordingObserver implements StructureObserver {
        private final List<PhysicsStructure> added = new ArrayList<>();
        private final List<PhysicsStructure> removed = new ArrayList<>();
        private final List<PhysicsStructure> refreshed = new ArrayList<>();

        @Override
        public void onStructureAdded(PhysicsStructure structure) {
            added.add(structure);
        }

        @Override
        public void onStructureRemoved(PhysicsStructure structure) {
            removed.add(structure);
        }

        @Override
        public void onStructureRefreshed(PhysicsStructure structure) {
            refreshed.add(structure);
        }

        private void clear() {
            added.clear();
            removed.clear();
            refreshed.clear();
        }
    }

    private static final class TestContainer implements StructureContainer {
        private final List<PhysicsStructure> structures;

        private TestContainer(List<PhysicsStructure> structures) {
            this.structures = List.copyOf(structures);
        }

        @Override
        public Level level() {
            return null;
        }

        @Override
        public List<PhysicsStructure> structures() {
            return structures;
        }
    }
}
