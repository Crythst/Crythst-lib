package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.FixedConstraintDef;
import io.mikaple.crythst.physics.constraint.HingeConstraintDef;
import io.mikaple.crythst.physics.constraint.PointConstraintDef;
import io.mikaple.crythst.physics.constraint.SliderConstraintDef;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertTransformEquals;
import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertVectorEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstraintConfigurationTest {
    @Test
    void fixedConfigurationMapsFramesIntoBodySpace() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0),
                            new Quaterniond().rotateY(Math.toRadians(20.0))));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 3.0, 4.0),
                            new Quaterniond().rotateZ(Math.toRadians(-15.0))));

            final Transform3d frameA = Transform3d.of(
                    new Vector3d(0.5, 1.0, 0.0), new Quaterniond().rotateX(0.2));
            final Transform3d frameB = Transform3d.of(
                    new Vector3d(-0.25, 0.75, 0.1), new Quaterniond().rotateY(0.3));
            final FixedConstraintConfiguration config =
                    FixedConstraintConfiguration.between(frameA, frameB);
            assertEquals(ConstraintType.FIXED, config.type());

            final FixedConstraintDef definition = assertInstanceOf(FixedConstraintDef.class,
                    config.toDefinition(a, b));
            assertEquals(ConstraintType.FIXED, definition.type());
            assertSame(a.body(), definition.bodyA());
            assertSame(b.body(), definition.bodyB());
            assertTransformEquals(ConstraintFrames.structureToBody(a, frameA),
                    definition.frameA(), 1.0e-12);
            assertTransformEquals(ConstraintFrames.structureToBody(b, frameB),
                    definition.frameB(), 1.0e-12);

            final FixedConstraintConfiguration worldConfig =
                    FixedConstraintConfiguration.toWorld(frameB);
            final FixedConstraintDef worldDefinition = assertInstanceOf(FixedConstraintDef.class,
                    worldConfig.toDefinition(a, null));
            assertNull(worldDefinition.bodyB());
            assertTransformEquals(ConstraintFrames.structureToBody(a, Transform3d.IDENTITY),
                    worldDefinition.frameA(), 1.0e-12);
            assertTransformEquals(frameB, worldDefinition.frameB(), 1.0e-12);
            assertTransformEquals(frameA, config.withFrameA(frameA).frameA(), 0.0);
            assertTransformEquals(frameB, config.withFrameB(frameB).frameB(), 0.0);

            a.close();
            b.close();
        }
    }

    @Test
    void freeConfigurationMapsAnchorsToPointJoints() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0),
                            new Quaterniond().rotateY(Math.toRadians(20.0))));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 3.0, 4.0),
                            new Quaterniond().rotateZ(Math.toRadians(-15.0))));

            final Vector3d anchorA = new Vector3d(0.5, 0.0, 1.0);
            final Vector3d anchorB = new Vector3d(-1.0, 2.0, 0.25);
            final FreeConstraintConfiguration config =
                    FreeConstraintConfiguration.between(anchorA, anchorB);
            assertEquals(ConstraintType.POINT, config.type());

            final PointConstraintDef definition = assertInstanceOf(PointConstraintDef.class,
                    config.toDefinition(a, b));
            assertEquals(ConstraintType.POINT, definition.type());
            assertSame(a.body(), definition.bodyA());
            assertSame(b.body(), definition.bodyB());
            assertVectorEquals(ConstraintFrames.structurePointToBody(a, anchorA),
                    definition.pivotA(), 1.0e-12);
            assertVectorEquals(ConstraintFrames.structurePointToBody(b, anchorB),
                    definition.pivotB(), 1.0e-12);

            final FreeConstraintConfiguration worldConfig =
                    FreeConstraintConfiguration.toWorld(anchorB);
            final PointConstraintDef worldDefinition = assertInstanceOf(PointConstraintDef.class,
                    worldConfig.toDefinition(a, null));
            assertNull(worldDefinition.bodyB());
            assertVectorEquals(ConstraintFrames.structurePointToBody(a, new Vector3d()),
                    worldDefinition.pivotA(), 1.0e-12);
            assertVectorEquals(anchorB, worldDefinition.pivotB(), 0.0);
            assertVectorEquals(anchorA, config.withAnchorA(anchorA).anchorA(), 0.0);
            assertVectorEquals(anchorB, config.withAnchorB(anchorB).anchorB(), 0.0);

            a.close();
            b.close();
        }
    }

    @Test
    void rotaryConfigurationMapsToHingeWithLimitsAndMotor() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0),
                            new Quaterniond().rotateY(Math.toRadians(20.0))));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 3.0, 4.0),
                            new Quaterniond().rotateZ(Math.toRadians(-15.0))));

            final Vector3d anchorA = new Vector3d(0.5, 0.0, 1.0);
            final Vector3d anchorB = new Vector3d(-1.0, 2.0, 0.25);
            final Vector3d axisA = new Vector3d(1.0, 0.0, 0.0);
            final Vector3d axisB = new Vector3d(0.0, 0.0, 1.0);
            final RotaryConstraintConfiguration config =
                    RotaryConstraintConfiguration.between(anchorA, axisA, anchorB, axisB)
                            .withLimits(-0.5, 0.75)
                            .withMotor(2.5, 12.0);
            assertEquals(ConstraintType.HINGE, config.type());

            final HingeConstraintDef definition = assertInstanceOf(HingeConstraintDef.class,
                    config.toDefinition(a, b));
            assertEquals(ConstraintType.HINGE, definition.type());
            assertSame(a.body(), definition.bodyA());
            assertSame(b.body(), definition.bodyB());
            assertVectorEquals(ConstraintFrames.structurePointToBody(a, anchorA),
                    definition.pivotA(), 1.0e-12);
            assertVectorEquals(ConstraintFrames.structurePointToBody(b, anchorB),
                    definition.pivotB(), 1.0e-12);
            assertVectorEquals(ConstraintFrames.structureDirectionToBody(a, axisA),
                    definition.axisA(), 1.0e-9);
            assertVectorEquals(ConstraintFrames.structureDirectionToBody(b, axisB),
                    definition.axisB(), 1.0e-9);
            assertEquals(-0.5, definition.lowerLimit(), 0.0);
            assertEquals(0.75, definition.upperLimit(), 0.0);
            assertTrue(definition.enableMotor());
            assertEquals(2.5, definition.motorTargetVelocity(), 0.0);
            assertEquals(12.0, definition.maxMotorImpulse(), 0.0);

            final RotaryConstraintConfiguration worldConfig = RotaryConstraintConfiguration.toWorld(
                    anchorA, axisA, anchorB, new Vector3d(0.0, 1.0, 0.0));
            final HingeConstraintDef worldDefinition = assertInstanceOf(HingeConstraintDef.class,
                    worldConfig.toDefinition(a, null));
            assertNull(worldDefinition.bodyB());
            assertVectorEquals(ConstraintFrames.structurePointToBody(a, anchorA),
                    worldDefinition.pivotA(), 1.0e-12);
            assertVectorEquals(anchorB, worldDefinition.pivotB(), 0.0);
            assertVectorEquals(ConstraintFrames.structureDirectionToBody(a, axisA),
                    worldDefinition.axisA(), 1.0e-9);
            assertVectorEquals(new Vector3d(0.0, 1.0, 0.0), worldDefinition.axisB(), 0.0);

            a.close();
            b.close();
        }
    }

    @Test
    void genericConfigurationMapsToSliderAlongFrameX() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final PhysicsStructure a = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(10.0, 2.0, 0.0),
                            new Quaterniond().rotateY(Math.toRadians(20.0))));
            final PhysicsStructure b = ConstraintTestSupport.createStructure(world,
                    Transform3d.of(new Vector3d(15.0, 3.0, 4.0),
                            new Quaterniond().rotateZ(Math.toRadians(-15.0))));

            final Transform3d frameA = Transform3d.of(
                    new Vector3d(0.5, 1.0, 0.0), new Quaterniond().rotateX(0.2));
            final Transform3d frameB = Transform3d.of(
                    new Vector3d(-0.25, 0.75, 0.1), new Quaterniond().rotateY(0.3));
            final GenericConstraintConfiguration config =
                    GenericConstraintConfiguration.slider(frameA, frameB, -1.5, 2.5)
                            .withAngularLimits(-0.25, 0.25)
                            .withMotor(0.75, 30.0);
            assertEquals(ConstraintType.SLIDER, config.type());

            final SliderConstraintDef definition = assertInstanceOf(SliderConstraintDef.class,
                    config.toDefinition(a, b));
            assertEquals(ConstraintType.SLIDER, definition.type());
            assertSame(a.body(), definition.bodyA());
            assertSame(b.body(), definition.bodyB());
            assertTransformEquals(ConstraintFrames.structureToBody(a, frameA),
                    definition.frameA(), 1.0e-12);
            assertTransformEquals(ConstraintFrames.structureToBody(b, frameB),
                    definition.frameB(), 1.0e-12);
            assertEquals(-1.5, definition.lowerLinearLimit(), 0.0);
            assertEquals(2.5, definition.upperLinearLimit(), 0.0);
            assertEquals(-0.25, definition.lowerAngularLimit(), 0.0);
            assertEquals(0.25, definition.upperAngularLimit(), 0.0);
            assertTrue(definition.enableLinearMotor());
            assertEquals(0.75, definition.targetLinearVelocity(), 0.0);
            assertEquals(30.0, definition.maxLinearForce(), 0.0);

            final GenericConstraintConfiguration worldConfig =
                    GenericConstraintConfiguration.toWorld(frameB, -2.0, 2.0);
            final SliderConstraintDef worldDefinition = assertInstanceOf(SliderConstraintDef.class,
                    worldConfig.toDefinition(a, null));
            assertNull(worldDefinition.bodyB());
            assertTransformEquals(ConstraintFrames.structureToBody(a, Transform3d.IDENTITY),
                    worldDefinition.frameA(), 1.0e-12);
            assertTransformEquals(frameB, worldDefinition.frameB(), 1.0e-12);

            a.close();
            b.close();
        }
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RotaryConstraintConfiguration.between(
                new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d()));
        assertThrows(IllegalArgumentException.class, () -> new GenericConstraintConfiguration(
                Transform3d.IDENTITY, Transform3d.IDENTITY,
                1.0, -1.0, 0.0, 0.0, false, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RotaryConstraintConfiguration(
                new Vector3d(), new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(), new Vector3d(0.0, 1.0, 0.0),
                1.0, -1.0, false, 0.0, 0.0));
        assertThrows(NullPointerException.class,
                () -> FixedConstraintConfiguration.between(null, Transform3d.IDENTITY));
    }
}
