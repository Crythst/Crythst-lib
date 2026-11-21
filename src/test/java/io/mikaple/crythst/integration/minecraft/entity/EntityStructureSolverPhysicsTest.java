package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the solver against a real {@link PhysicsStructure}, including the
 * centre-of-mass mapping between voxel coordinates and world space.
 */
class EntityStructureSolverPhysicsTest {
    private static final double MARGIN = 0.02;

    @Test
    void realStructureBlockCentreIsFoundByTheSolver() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure structure = createTiltedStructure(world);
            final VoxelBox box = structure.collider().boxes().get(0);
            final double scale = structure.blockScale();
            final Vector3d blockCenter = structure.blockCenter(box.minX(), box.minY(), box.minZ());
            final double top = blockCenter.y + scale * 0.5;

            final Aabb entityBox = new Aabb(
                    new Vector3d(blockCenter.x - 0.2, top - 0.05, blockCenter.z - 0.2),
                    new Vector3d(blockCenter.x + 0.2, top + 1.55, blockCenter.z + 0.2)
            );

            final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                    entityBox, structure.pose(), structure.collider().boxes(), scale, MARGIN, 256);

            assertNotNull(hit);
            assertTrue(hit.normal().y > 0.99, "expected an upward normal, got " + hit.normal());
            assertEquals(0.05, hit.penetration(), 1.0e-6);
            structure.close();
        }
    }

    @Test
    void resolvingEntitiesDoesNotTouchTheStructureBody() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure structure = createTiltedStructure(world);
            final Transform3d before = structure.body().transform();
            final VoxelBox box = structure.collider().boxes().get(0);
            final Vector3d blockCenter = structure.blockCenter(box.minX(), box.minY(), box.minZ());
            final double top = blockCenter.y + structure.blockScale() * 0.5;
            final Aabb entityBox = new Aabb(
                    new Vector3d(blockCenter.x - 0.2, top - 0.05, blockCenter.z - 0.2),
                    new Vector3d(blockCenter.x + 0.2, top + 1.55, blockCenter.z + 0.2)
            );

            for (int i = 0; i < 100; i++) {
                assertNotNull(EntityStructureSolver.resolve(entityBox, structure.pose(),
                        structure.collider().boxes(), structure.blockScale(), MARGIN, 256));
            }

            final Transform3d after = structure.body().transform();
            assertEquals(before.position().x, after.position().x, 1.0e-12);
            assertEquals(before.position().y, after.position().y, 1.0e-12);
            assertEquals(before.position().z, after.position().z, 1.0e-12);
            assertEquals(before.rotation().x, after.rotation().x, 1.0e-12);
            assertEquals(before.rotation().y, after.rotation().y, 1.0e-12);
            assertEquals(before.rotation().z, after.rotation().z, 1.0e-12);
            assertEquals(before.rotation().w, after.rotation().w, 1.0e-12);
            structure.close();
        }
    }

    @Test
    void rotatedStructureBroadphaseCoversEveryBlockCorner() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 2, 3);
            final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, 1000.0, PhysicsMaterial.STONE);
            final Quaterniond rotation = new Quaterniond()
                    .rotateX(0.41)
                    .rotateY(0.93)
                    .rotateZ(-0.37);
            final Vector3d translation = new Vector3d(1.0, 8.0, -2.0);
            final PhysicsStructure structure = new PhysicsStructure(world, StructureDef.builder(volume)
                    .type(BodyType.STATIC)
                    .pose(Transform3d.of(translation, rotation))
                    .build());

            final Aabb bulletBounds = structure.aabb();
            final Aabb derivedBounds = structure.obb().toAabb();
            final double scale = structure.blockScale();
            for (VoxelBox box : structure.collider().boxes()) {
                for (int i = 0; i < 8; i++) {
                    final Vector3d corner = structure.pose().transformPoint(new Vector3d(
                            ((i & 1) == 0 ? box.minX() : box.minX() + box.sizeX()) * scale,
                            ((i & 2) == 0 ? box.minY() : box.minY() + box.sizeY()) * scale,
                            ((i & 4) == 0 ? box.minZ() : box.minZ() + box.sizeZ()) * scale
                    ));
                    assertTrue(bulletBounds.expand(1.0e-6).contains(corner),
                            "Bullet AABB missed corner " + corner);
                    assertTrue(derivedBounds.expand(1.0e-6).contains(corner),
                            "OrientedBox AABB missed corner " + corner);
                }
            }
            // Bullet's AABB includes its collision margin, so it should contain
            // the tight derived AABB rather than match it exactly.
            assertTrue(bulletBounds.expand(0.25).contains(derivedBounds.min()));
            assertTrue(bulletBounds.expand(0.25).contains(derivedBounds.max()));
            structure.close();
        }
    }

    private static PhysicsStructure createTiltedStructure(PhysicsWorld world) {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 1, 3);
        final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, 1000.0, PhysicsMaterial.STONE);
        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(25.0));
        final Vector3d center = new Vector3d(4.5, 6.0, 4.5);
        final Vector3d translation = new Vector3d(center)
                .sub(new Vector3d(1.5, 0.5, 1.5).rotate(rotation));
        return new PhysicsStructure(world, StructureDef.builder(volume)
                .type(BodyType.STATIC)
                .pose(Transform3d.of(translation, rotation))
                .build());
    }
}
