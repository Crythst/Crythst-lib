package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureMassTrackerTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void perCellAndTotalMassUseTheStructureGrid() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final MutableVoxelVolume volume = new MutableVoxelVolume(new IntBounds3(0, 0, 0, 2, 1, 1));
            volume.setBlock(0, 0, 0, true, 100.0, PhysicsMaterial.STONE);
            volume.setBlock(1, 0, 0, true, 300.0, PhysicsMaterial.STONE);
            final PhysicsStructure structure = new PhysicsStructure(world,
                    StructureDef.builder(volume)
                            .type(BodyType.STATIC)
                            .pose(Transform3d.of(new Vector3d(10.0, 20.0, 30.0)))
                            .build());

            final StructureMassTracker tracker = StructureMassTracker.of(structure);
            assertEquals(400.0, tracker.mass(), EPSILON);
            assertEquals(400.0, tracker.summedCellMass(), EPSILON);
            assertEquals(2, tracker.solidCellCount());
            assertEquals(100.0, tracker.cellMass(0, 0, 0), EPSILON);
            assertEquals(300.0, tracker.cellMass(1, 0, 0), EPSILON);
            assertEquals(0.0, tracker.cellMass(5, 0, 0), EPSILON);
            assertTrue(tracker.isSolid(1, 0, 0));
            assertFalse(tracker.isSolid(2, 0, 0));

            final Vector3d local = tracker.centerOfMass();
            // Weighted centre = (100*0.5 + 300*1.5) / 400 = 1.25 in x.
            assertEquals(1.25, local.x, EPSILON);
            assertEquals(0.5, local.y, EPSILON);
            assertEquals(0.5, local.z, EPSILON);

            final Vector3d worldCom = tracker.centerOfMassWorld();
            assertEquals(11.25, worldCom.x, EPSILON);
            assertEquals(20.5, worldCom.y, EPSILON);
            assertEquals(30.5, worldCom.z, EPSILON);

            final Vector3d block = structure.blockCenter(1, 0, 0);
            assertEquals(300.0, tracker.massAtWorld(block), EPSILON);
            assertEquals(0.0, tracker.massAtWorld(new Vector3d(100.0, 100.0, 100.0)), EPSILON);
            assertNull(tracker.cellCenterWorld(9, 0, 0, new Vector3d()));

            structure.close();
        }
    }
}
