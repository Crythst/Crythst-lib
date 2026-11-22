package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.DofAxisDef;
import io.mikaple.crythst.physics.constraint.SixDofConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertTransformEquals;
import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertVectorEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SixDofConstraintConfigurationTest {
    @Test
    void mapsSixAxesAndFramesIntoTheBodySpace() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0),
                            new Quaterniond().rotateY(Math.toRadians(20.0))));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 3.0, 4.0),
                            new Quaterniond().rotateZ(Math.toRadians(-15.0))));

            final Transform3d frameA = Transform3d.of(new Vector3d(0.5, 1.0, 0.0),
                    new Quaterniond().rotateX(0.2));
            final Transform3d frameB = Transform3d.of(new Vector3d(-0.25, 0.75, 0.1),
                    new Quaterniond().rotateY(0.3));
            final DofAxisConfiguration locked = DofAxisConfiguration.locked();
            final DofAxisConfiguration free = DofAxisConfiguration.free();
            final SixDofConstraintConfiguration config = SixDofConstraintConfiguration.between(
                    frameA, frameB,
                    DofAxisConfiguration.limited(-1.0, 2.0).withMotor(0.5, 40.0),
                    locked, free,
                    DofAxisConfiguration.limited(-0.25, 0.25).withSpring(12.0, 0.7),
                    locked, free);

            assertEquals(ConstraintType.SIX_DOF, config.type());
            final SixDofConstraintDef definition = assertInstanceOf(SixDofConstraintDef.class,
                    config.toDefinition(a, b));
            assertSame(a.body(), definition.bodyA());
            assertSame(b.body(), definition.bodyB());
            assertTransformEquals(ConstraintFrames.structureToBody(a, frameA),
                    definition.frameA(), 1.0e-12);
            assertTransformEquals(ConstraintFrames.structureToBody(b, frameB),
                    definition.frameB(), 1.0e-12);

            final DofAxisDef linearX = definition.axis(SixDofAxis.LINEAR_X.index());
            assertEquals(-1.0, linearX.lowerLimit(), 0.0);
            assertEquals(2.0, linearX.upperLimit(), 0.0);
            assertFalse(linearX.freeAxis());
            assertTrue(linearX.enableMotor());
            assertEquals(0.5, linearX.motorTargetVelocity(), 0.0);
            assertEquals(40.0, linearX.maxMotorForce(), 0.0);

            assertTrue(definition.axis(SixDofAxis.LINEAR_Z.index()).freeAxis());
            assertFalse(definition.axis(SixDofAxis.LINEAR_Y.index()).freeAxis());

            final DofAxisDef angularX = definition.axis(SixDofAxis.ANGULAR_X.index());
            assertEquals(-0.25, angularX.lowerLimit(), 0.0);
            assertEquals(0.25, angularX.upperLimit(), 0.0);
            assertTrue(angularX.enableSpring());
            assertEquals(12.0, angularX.stiffness(), 0.0);
            assertEquals(0.7, angularX.damping(), 0.0);

            a.close();
            b.close();
        }
    }

    @Test
    void worldAnchoredConfigurationKeepsWorldFrame() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0),
                            new Quaterniond().rotateY(0.4)));
            final Transform3d worldFrame = Transform3d.of(new Vector3d(100.0, 70.0, -3.0),
                    new Quaterniond().rotateZ(0.6));
            final SixDofConstraintConfiguration config =
                    SixDofConstraintConfiguration.freeToWorld(worldFrame);

            final SixDofConstraintDef definition = assertInstanceOf(SixDofConstraintDef.class,
                    config.toDefinition(a, null));
            assertNull(definition.bodyB());
            assertTransformEquals(ConstraintFrames.structureToBody(a, Transform3d.IDENTITY),
                    definition.frameA(), 1.0e-12);
            assertTransformEquals(worldFrame, definition.frameB(), 1.0e-12);
            for (DofAxisDef axis : definition.axes()) {
                assertTrue(axis.freeAxis());
            }

            a.close();
        }
    }

    @Test
    void anchorAndAxisRoundTripThroughImmutableConfiguration() {
        final Transform3d frameA = Transform3d.of(new Vector3d(1.0, 2.0, 3.0),
                new Quaterniond().rotateX(0.1).rotateY(0.2));
        final Transform3d frameB = Transform3d.of(new Vector3d(-4.0, 5.0, 6.0),
                new Quaterniond().rotateZ(-0.3));
        final SixDofConstraintConfiguration config = SixDofConstraintConfiguration.locked(
                frameA, frameB);

        assertVectorEquals(frameA.position(), config.localFrameA().position(), 0.0);
        assertVectorEquals(frameB.position(), config.localFrameB().position(), 0.0);
        assertTransformEquals(frameA, config.localFrameA(), 0.0);
        assertTransformEquals(frameB, config.localFrameB(), 0.0);
        assertEquals(frameA, config.withAxis(SixDofAxis.ANGULAR_Y,
                DofAxisConfiguration.free()).localFrameA());
        assertEquals(DofAxisConfiguration.free(),
                config.withAxis(SixDofAxis.ANGULAR_Y, DofAxisConfiguration.free())
                        .axis(SixDofAxis.ANGULAR_Y));
    }

    @Test
    void invalidAxisConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DofAxisConfiguration.limited(1.0, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DofAxisConfiguration(false, 0.0, 0.0,
                        false, 0.0, -1.0, false, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DofAxisConfiguration(false, 0.0, 0.0,
                        false, Double.NaN, 0.0, false, 0.0, 0.0));
        assertThrows(NullPointerException.class,
                () -> new SixDofConstraintConfiguration(Transform3d.IDENTITY,
                        Transform3d.IDENTITY, null, DofAxisConfiguration.free(),
                        DofAxisConfiguration.free(), DofAxisConfiguration.free(),
                        DofAxisConfiguration.free(), DofAxisConfiguration.free()));
        assertThrows(IllegalArgumentException.class, () -> SixDofAxis.ofIndex(6));
        // A free axis may carry arbitrary (ignored) limits.
        final DofAxisConfiguration free = new DofAxisConfiguration(true, 5.0, -5.0,
                false, 0.0, 0.0, false, 0.0, 0.0);
        assertTrue(free.disabled());
    }
}
