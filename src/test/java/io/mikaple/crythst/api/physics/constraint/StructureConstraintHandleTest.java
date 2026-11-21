package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstraintHandleTest {
    @Test
    void handleDelegatesLifecycleToBulletConstraint() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world, pose(10.0, 2.0, 0.0));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world, pose(15.0, 3.0, 4.0));
            final PhysicsStructure c = ConstraintTestSupport.createStructure(world, pose(20.0, 4.0, 8.0));

            final ConstraintHandle raw = world.addConstraint(
                    FixedConstraintConfiguration.between(Transform3d.IDENTITY, Transform3d.IDENTITY)
                            .toDefinition(a, b));
            final StructureConstraintHandle handle =
                    new StructureConstraintHandle(world, raw, a, b);

            assertTrue(handle.isValid());
            assertEquals(raw.id(), handle.id());
            assertEquals(ConstraintType.FIXED, handle.type());
            assertTrue(handle.enabled());
            assertTrue(handle.involves(a));
            assertTrue(handle.involves(b));
            assertFalse(handle.involves(c));
            assertFalse(handle.involves(null));
            assertFalse(handle.supportsMotor());
            assertThrows(UnsupportedOperationException.class, () -> handle.isMotorEnabled());

            handle.setEnabled(false);
            assertFalse(handle.enabled());
            assertTrue(handle.isValid());
            handle.setEnabled(true);
            assertTrue(handle.enabled());

            handle.remove();
            assertFalse(handle.isValid());
            assertEquals(0, world.constraintCount());
            handle.remove();
            assertThrows(IllegalStateException.class, () -> handle.enabled());

            a.close();
            b.close();
            c.close();
        }
    }

    @Test
    void worldAnchoredHandleStopsBeingValidWhenItsStructureCloses() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world, pose(10.0, 2.0, 0.0));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world, pose(15.0, 3.0, 4.0));

            final ConstraintHandle raw = world.addConstraint(
                    FixedConstraintConfiguration.toWorld(Transform3d.IDENTITY).toDefinition(a, null));
            final StructureConstraintHandle handle =
                    new StructureConstraintHandle(world, raw, a, null);

            assertTrue(handle.isValid());
            assertTrue(handle.involves(a));
            assertFalse(handle.involves(b));

            a.close();
            assertFalse(handle.isValid());
            handle.remove();

            b.close();
        }
    }

    @Test
    void hingeMotorAccessorsDelegateToBullet() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world, pose(10.0, 2.0, 0.0));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world, pose(15.0, 3.0, 4.0));

            final RotaryConstraintConfiguration config =
                    RotaryConstraintConfiguration.between(
                                    new Vector3d(), new Vector3d(1.0, 0.0, 0.0),
                                    new Vector3d(), new Vector3d(1.0, 0.0, 0.0))
                            .withMotor(3.0, 9.0);
            final ConstraintHandle raw = world.addConstraint(config.toDefinition(a, b));
            final StructureConstraintHandle handle =
                    new StructureConstraintHandle(world, raw, a, b);

            assertTrue(handle.supportsMotor());
            assertTrue(handle.isMotorEnabled());
            assertEquals(3.0, handle.motorTargetVelocity(), 1.0e-4);
            assertEquals(9.0, handle.maxMotorImpulse(), 1.0e-4);

            handle.setMotorTargetVelocity(4.5);
            assertEquals(4.5, handle.motorTargetVelocity(), 1.0e-4);
            handle.setMaxMotorImpulse(11.0);
            assertEquals(11.0, handle.maxMotorImpulse(), 1.0e-4);
            handle.setMotorEnabled(false);
            assertFalse(handle.isMotorEnabled());
            handle.setMotorEnabled(true);
            assertTrue(handle.isMotorEnabled());

            handle.remove();
            a.close();
            b.close();
        }
    }

    @Test
    void worldAnchoredFixedConstraintHoldsStructureAgainstGravity() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure structure =
                    ConstraintTestSupport.createStructure(world, pose(0.0, 5.0, 0.0));
            final Transform3d anchor = structure.pose();
            final ConstraintHandle raw = world.addConstraint(
                    FixedConstraintConfiguration.toWorld(anchor).toDefinition(structure, null));
            assertEquals(1, world.constraintCount());

            for (int i = 0; i < 120; i++) {
                world.step(1.0 / 60.0);
            }

            final Transform3d after = structure.pose();
            assertEquals(anchor.position().x, after.position().x, 0.1);
            assertEquals(anchor.position().y, after.position().y, 0.1);
            assertEquals(anchor.position().z, after.position().z, 0.1);

            world.removeConstraint(raw);
            structure.close();
        }
    }

    private static Transform3d pose(double x, double y, double z) {
        return Transform3d.of(new Vector3d(x, y, z),
                new Quaterniond().rotateY(Math.toRadians(20.0)));
    }
}
