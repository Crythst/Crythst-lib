package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstraintAccessTest {
    @Test
    void structureCanCreateListAndRemoveItsOwnConstraints() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0)));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 2.0, 0.0)));

            final StructureConstraintHandle handle = a.addConstraint(b,
                    FixedConstraintConfiguration.between(Transform3d.IDENTITY,
                            Transform3d.IDENTITY));
            assertTrue(handle.isValid());
            assertEquals(1, a.constraints().size());
            assertEquals(1, b.constraints().size());
            assertTrue(handle.involves(b));

            assertTrue(a.removeConstraint(handle));
            assertFalse(handle.isValid());
            assertTrue(a.constraints().isEmpty());
            assertFalse(a.removeConstraint(handle));

            final StructureConstraintHandle worldHandle = a.addConstraintToWorld(
                    FixedConstraintConfiguration.toWorld(
                            Transform3d.of(new Vector3d(10.0, 20.0, 0.0))));
            assertTrue(worldHandle.isValid());
            assertTrue(a.constraints().stream().anyMatch(h -> h.id() == worldHandle.id()));
            assertTrue(a.removeConstraint(worldHandle));

            a.close();
            b.close();
        }
    }
}
