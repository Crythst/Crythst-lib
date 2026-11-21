package io.mikaple.crythst.physics.structure;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsStructureTest {
    @Test
    void tiltedStructureFallsAndKeepsItsRenderPose() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final IntBounds3 groundBounds = new IntBounds3(0, 0, 0, 9, 1, 9);
            final MutableVoxelVolume ground = VoxelVolumes.filled(groundBounds, 1000.0, PhysicsMaterial.STONE);
            final PhysicsStructure floor = world == null ? null : new PhysicsStructure(world,
                    StructureDef.builder(ground)
                            .type(io.mikaple.crythst.physics.body.BodyType.STATIC)
                            .pose(Transform3d.of(new Vector3d(0, 0, 0)))
                            .build());
            assertNotNull(floor);

            final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 1, 3);
            final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, 1000.0, PhysicsMaterial.STONE);
            final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(25.0));
            final Vector3d center = new Vector3d(4.5, 6.0, 4.5);
            final Vector3d translation = new Vector3d(center)
                    .sub(new Vector3d(1.5, 0.5, 1.5).rotate(rotation));
            final PhysicsStructure structure = new PhysicsStructure(world,
                    StructureDef.builder(volume)
                            .pose(Transform3d.of(translation, rotation))
                            .build());

            for (int i = 0; i < 240; i++) {
                world.step(1.0 / 60.0);
                structure.syncRenderPose();
            }

            final Transform3d pose = structure.pose();
            assertTrue(pose.position().y < 4.0, "structure should fall, y=" + pose.position().y);
            assertTrue(pose.position().y > 0.2, "structure should not sink through floor, y=" + pose.position().y);
            final StructureRenderState render = structure.renderState(0.5);
            assertNotNull(render.bounds());
            assertTrue(render.collider().boxes().size() > 0);
            assertEquals(structure.body().id(), render.bodyId());

            floor.close();
            structure.close();
        }
    }
}
