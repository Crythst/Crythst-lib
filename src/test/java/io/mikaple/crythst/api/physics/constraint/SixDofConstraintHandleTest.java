package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertTransformEquals;
import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertVectorEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SixDofConstraintHandleTest {
    @Test
    void handleExposesFramesAxesLimitsMotorsAndSprings() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0)));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 2.0, 0.0)));
            final Transform3d frameA = Transform3d.of(new Vector3d(0.5, 0.0, 0.0));
            final Transform3d frameB = Transform3d.of(new Vector3d(-0.5, 0.0, 0.0));
            final StructureConstraintHandle handle = a.addConstraint(b,
                    SixDofConstraintConfiguration.locked(frameA, frameB));

            assertTrue(handle.isValid());
            assertEquals(ConstraintType.SIX_DOF, handle.type());
            assertTrue(handle.supportsDofControl());
            assertFalse(handle.isWorldAnchored());
            assertNotNull(handle.configuration());
            assertTransformEquals(frameA, handle.localFrameA(), 0.0);
            assertTransformEquals(frameB, handle.localFrameB(), 0.0);
            assertVectorEquals(frameA.position(), handle.localAnchorA(), 0.0);
            assertVectorEquals(frameB.position(), handle.localAnchorB(), 0.0);
            assertEquals(handle.localFrameA().rotation(), handle.axisQuaternionA());

            handle.setLimits(SixDofAxis.LINEAR_X, -1.5, 2.5);
            assertEquals(-1.5, handle.lowerLimit(SixDofAxis.LINEAR_X), 1.0e-6);
            assertEquals(2.5, handle.upperLimit(SixDofAxis.LINEAR_X), 1.0e-6);
            assertFalse(handle.isAxisFree(SixDofAxis.LINEAR_X));
            assertThrows(IllegalArgumentException.class,
                    () -> handle.setLimits(SixDofAxis.LINEAR_X, 2.0, 1.0));

            handle.setAxisFree(SixDofAxis.ANGULAR_Y, true);
            assertTrue(handle.isAxisFree(SixDofAxis.ANGULAR_Y));
            handle.setAxisFree(SixDofAxis.ANGULAR_Y, false);
            assertFalse(handle.isAxisFree(SixDofAxis.ANGULAR_Y));

            handle.setDofMotor(SixDofAxis.LINEAR_X, true, 0.75, 120.0);
            assertTrue(handle.isDofMotorEnabled(SixDofAxis.LINEAR_X));
            assertEquals(0.75, handle.dofMotorTargetVelocity(SixDofAxis.LINEAR_X), 1.0e-6);
            assertEquals(120.0, handle.dofMaxMotorForce(SixDofAxis.LINEAR_X), 1.0e-3);

            handle.setDofSpring(SixDofAxis.ANGULAR_X, true, 8.0, 0.5);
            assertTrue(handle.isDofSpringEnabled(SixDofAxis.ANGULAR_X));
            assertEquals(8.0, handle.dofStiffness(SixDofAxis.ANGULAR_X), 1.0e-3);
            assertEquals(0.5, handle.dofDamping(SixDofAxis.ANGULAR_X), 1.0e-3);

            handle.setFeedbackEnabled(true);
            assertTrue(handle.feedbackEnabled());
            world.step(1.0 / 60.0);
            assertTrue(Double.isFinite(handle.appliedImpulse()));

            handle.setEnabled(false);
            assertFalse(handle.enabled());
            handle.setEnabled(true);
            assertTrue(handle.enabled());

            // Constraints re-wrapped by the structure retain the API metadata.
            final List<StructureConstraintHandle> wrapped = a.constraints();
            final StructureConstraintHandle found = assertInstanceOf(StructureConstraintHandle.class,
                    wrapped.stream().filter(h -> h.id() == handle.id()).findFirst().orElseThrow());
            assertInstanceOf(SixDofConstraintConfiguration.class, found.configuration());
            assertNotNull(found.worldFrameA());
            assertNotNull(found.worldFrameB());

            assertTrue(a.removeConstraint(handle));
            assertFalse(handle.isValid());
            a.close();
            b.close();
        }
    }

    @Test
    void worldAnchoredHandleExposesWorldAnchorAndCleanup() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0)));
            final Transform3d worldFrame = Transform3d.of(new Vector3d(10.0, 8.0, 0.0));
            final StructureConstraintHandle handle = a.addConstraintToWorld(
                    SixDofConstraintConfiguration.freeToWorld(worldFrame));

            assertTrue(handle.isWorldAnchored());
            assertVectorEquals(worldFrame.position(), handle.worldAnchorB(), 0.0);
            ConstraintTestSupport.assertQuaternionEquals(worldFrame.rotation(),
                    handle.axisQuaternionB(), 0.0);
            assertEquals(1, a.constraints().size());
            assertEquals(1, a.removeAllConstraints());
            assertTrue(a.constraints().isEmpty());
            assertFalse(handle.isValid());

            a.close();
        }
    }
}
