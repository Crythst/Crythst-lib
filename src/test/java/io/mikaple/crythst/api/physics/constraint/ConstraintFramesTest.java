package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertTransformEquals;
import static io.mikaple.crythst.api.physics.constraint.ConstraintTestSupport.assertVectorEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstraintFramesTest {
    @Test
    void structureToBodyAccountsForCenterOfMassAndRoundTrips() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final Transform3d pose = Transform3d.of(
                    new Vector3d(12.0, 4.0, -7.0),
                    new Quaterniond().rotateY(Math.toRadians(30.0)).rotateX(Math.toRadians(10.0)));
            final PhysicsStructure structure = ConstraintTestSupport.createStructure(world, pose);
            final Vector3d centerOfMass = ConstraintTestSupport.centerOfMassOffset(structure);

            final Transform3d localFrame = Transform3d.of(
                    new Vector3d(0.25, 0.5, -0.75),
                    new Quaterniond().rotateZ(0.4));
            final Transform3d bodyFrame = ConstraintFrames.structureToBody(structure, localFrame);

            // B^-1 * S is a pure translation by -COM offset, so the rotation is
            // unchanged and the position is local - COM.
            assertEquals(localFrame.position().x - centerOfMass.x, bodyFrame.position().x, 1.0e-6);
            assertEquals(localFrame.position().y - centerOfMass.y, bodyFrame.position().y, 1.0e-6);
            assertEquals(localFrame.position().z - centerOfMass.z, bodyFrame.position().z, 1.0e-6);
            assertTransformEquals(localFrame, ConstraintFrames.bodyToStructure(structure, bodyFrame),
                    1.0e-6);

            final Transform3d worldFrame = ConstraintFrames.structureToWorld(structure, localFrame);
            assertVectorEquals(pose.transformPoint(localFrame.position()), worldFrame.position(), 1.0e-6);
            assertTransformEquals(localFrame, ConstraintFrames.worldToStructure(structure, worldFrame),
                    1.0e-6);

            final Vector3d localPoint = new Vector3d(0.1, -0.2, 0.3);
            final Vector3d bodyPoint = ConstraintFrames.structurePointToBody(structure, localPoint);
            assertVectorEquals(new Vector3d(localPoint).sub(centerOfMass), bodyPoint, 1.0e-6);
            assertVectorEquals(localPoint,
                    ConstraintFrames.bodyPointToStructure(structure, bodyPoint), 1.0e-6);

            final Vector3d worldPoint = pose.transformPoint(localPoint);
            assertVectorEquals(worldPoint,
                    ConstraintFrames.structurePointToWorld(structure, localPoint), 1.0e-6);
            assertVectorEquals(localPoint,
                    ConstraintFrames.worldPointToStructure(structure, worldPoint), 1.0e-6);

            final Vector3d localDirection = new Vector3d(1.0, 2.0, 3.0).normalize();
            assertVectorEquals(localDirection,
                    ConstraintFrames.structureDirectionToBody(structure, localDirection), 1.0e-6);
            assertVectorEquals(localDirection,
                    ConstraintFrames.bodyDirectionToStructure(structure, localDirection), 1.0e-6);

            final Vector3d worldDirection = pose.transformDirection(localDirection);
            assertVectorEquals(worldDirection,
                    ConstraintFrames.structureDirectionToWorld(structure, localDirection), 1.0e-6);
            assertVectorEquals(localDirection,
                    ConstraintFrames.worldDirectionToStructure(structure, worldDirection), 1.0e-6);

            structure.close();
        }
    }
}
