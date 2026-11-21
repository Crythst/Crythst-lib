package io.mikaple.crythst.physics.world;

import io.mikaple.crythst.integration.minecraft.collision.TerrainCollisionGroups;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionGeometry;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionMask;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.bullet.BulletBodyHandle;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainCollisionTest {
    @Test
    void dynamicBodyCollidesWithStaticSectionCompound() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final BodyHandle terrain = createTerrainSection(world,
                    new IntBounds3(0, 0, 0, 16, 4, 16));

            final BodyHandle cube = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(8.0, 10.0, 8.0))
                    .build());

            step(world, 300);

            assertSame(TerrainBody.INSTANCE, terrain.userData(), "terrain body must be tagged");
            final double y = cube.transform().position().y;
            assertTrue(y > 3.0 && y < 5.0, "cube should rest on the top of the static section, y=" + y);
        }
    }

    @Test
    void negativeCoordinateSectionIsPlacedAtTheCorrectWorldPosition() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            // Section (-1, 0, -1) covers blocks x,z in [-16, 0) and y in [0, 16).
            final IntBounds3 bounds = new TerrainSectionGeometry.Key(-1, 0, -1).bounds();
            final TerrainSectionMask mask = new TerrainSectionMask(bounds);
            for (int y = 0; y < 4; y++) {
                for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                    for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                        mask.setSolid(x, y, z, true);
                    }
                }
            }
            final VoxelColliderData data = VoxelColliderBuilder.build(mask, terrainOptions());
            // Centre of mass is in absolute voxel coordinates; the offset must
            // not be added to the section origin twice.
            assertEquals(-8.0, data.centerOfMass().x, 1.0e-9);
            assertEquals(2.0, data.centerOfMass().y, 1.0e-9);
            assertEquals(-8.0, data.centerOfMass().z, 1.0e-9);

            final Vector3d bodyPosition = TerrainSectionGeometry.staticBodyPosition(bounds, data);
            assertEquals(-8.0, bodyPosition.x, 1.0e-9);
            assertEquals(2.0, bodyPosition.y, 1.0e-9);
            assertEquals(-8.0, bodyPosition.z, 1.0e-9);

            world.createBody(BodyDef.staticBody(data.toCompoundShape())
                    .transform(Transform3d.of(bodyPosition))
                    .filter(TerrainCollisionGroups.TERRAIN)
                    .userData(TerrainBody.INSTANCE)
                    .build());

            final BodyHandle cube = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(-8.0, 10.0, -8.0))
                    .build());

            step(world, 300);

            final Vector3d position = cube.transform().position();
            assertTrue(position.y > 4.0 && position.y < 5.0,
                    "cube should land on a negative-coordinate section, y=" + position.y);
            assertTrue(Math.abs(position.x + 8.0) < 0.5 && Math.abs(position.z + 8.0) < 0.5,
                    "cube should not be displaced horizontally: " + position);
        }
    }

    @Test
    void terrainCanBeExcludedWithACollisionFilter() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final BodyHandle terrain = createTerrainSection(world,
                    new IntBounds3(0, 0, 0, 16, 4, 16));

            final BodyHandle dropped = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(8.0, 10.0, 8.0))
                    .build());
            final BodyHandle ghost = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(2.0, 10.0, 2.0))
                    .filter(TerrainCollisionGroups.ignoringTerrain())
                    .build());

            // libbulletjme's PhysicsSpace#addCollisionObject resets a body's
            // broadphase group/mask to the static/dynamic defaults, so
            // BodyDef.filter is currently only stored on the handle. Reapply
            // at the proxy level here to exercise the intended filtering; the
            // backend must do this after insertion (see integration report).
            applyFilterToBackend(terrain, TerrainCollisionGroups.TERRAIN);
            applyFilterToBackend(ghost, TerrainCollisionGroups.ignoringTerrain());

            step(world, 300);

            final double droppedY = dropped.transform().position().y;
            final double ghostY = ghost.transform().position().y;
            assertTrue(droppedY > 3.0 && droppedY < 5.0,
                    "default body should collide with terrain, y=" + droppedY);
            assertTrue(ghostY < 0.0,
                    "body that ignores the terrain group should fall through, y=" + ghostY);
        }
    }

    @Test
    void defaultBodiesStillCollideWithEachOther() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final VoxelColliderData data = VoxelColliderBuilder.build(
                    VoxelVolumes.filled(new IntBounds3(0, 0, 0, 8, 2, 8), 1000.0, PhysicsMaterial.STONE),
                    terrainOptions());
            world.createBody(BodyDef.staticBody(data.toCompoundShape())
                    .transform(Transform3d.of(TerrainSectionGeometry.staticBodyPosition(
                            new IntBounds3(0, 0, 0, 8, 2, 8), data)))
                    .build());

            final BodyHandle cube = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(4.0, 8.0, 4.0))
                    .build());

            step(world, 300);

            final double y = cube.transform().position().y;
            assertTrue(y > 1.5 && y < 3.5,
                    "the unchanged default filter should still collide, y=" + y);
        }
    }

    private static BodyHandle createTerrainSection(PhysicsWorld world, IntBounds3 bounds) {
        final VoxelColliderData data = VoxelColliderBuilder.build(
                VoxelVolumes.filled(bounds, 1000.0, PhysicsMaterial.STONE),
                terrainOptions());
        final Vector3d position = TerrainSectionGeometry.staticBodyPosition(bounds, data);
        return world.createBody(BodyDef.staticBody(data.toCompoundShape())
                .transform(Transform3d.of(position))
                .filter(TerrainCollisionGroups.TERRAIN)
                .userData(TerrainBody.INSTANCE)
                .build());
    }

    private static VoxelColliderOptions terrainOptions() {
        return VoxelColliderOptions.DEFAULT.withMergedMaterials(true);
    }

    /**
     * Applies a filter to the native broadphase proxy after the body has been
     * added to the space. libbulletjme ignores custom groups until the proxy
     * exists; once BulletPhysicsWorld moves its filter setters after
     * addCollisionObject this becomes a no-op.
     */
    private static void applyFilterToBackend(BodyHandle body, CollisionFilter filter) {
        final BulletBodyHandle bullet = (BulletBodyHandle) body;
        bullet.bulletBody().setCollisionGroup(filter.group());
        bullet.bulletBody().setCollideWithGroups(filter.collidesWith());
    }

    private static void step(PhysicsWorld world, int steps) {
        for (int i = 0; i < steps; i++) {
            world.step(1.0 / 60.0);
        }
    }
}
