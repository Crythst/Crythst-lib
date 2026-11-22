package io.mikaple.crythst.api.block;

import io.mikaple.crythst.api.physics.MassData;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.QueuedForceGroup;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockStructureLiftProviderTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void defaultActorQueuesLiftAtCentreOfMass() {
        final QueuedForceGroup group = new QueuedForceGroup(
                new ForceGroup(Component.literal("test")));
        final TestContext context = new TestContext(group, new Vector3d(1.0, 2.0, 3.0),
                Transform3d.IDENTITY);
        final BlockStructureLiftProvider lift = ctx -> new Vector3d(0.0, 500.0, 0.0);

        group.reset(context.centerOfMassWorld());
        lift.structurePhysicsTick(context);

        final Vector3d force = group.accumulatedForce(new Vector3d());
        assertEquals(0.0, force.x, EPSILON);
        assertEquals(500.0, force.y, EPSILON);
        assertEquals(0.0, force.z, EPSILON);
        assertEquals(0.0, group.accumulatedTorque(new Vector3d()).length(), EPSILON);
    }

    @Test
    void propellerRotatesLocalAxisThroughTheStructurePose() {
        final Transform3d pose = Transform3d.of(new Vector3d(),
                new Quaterniond().rotateZ(Math.toRadians(90.0)));
        final TestContext context = new TestContext(new QueuedForceGroup(
                new ForceGroup(Component.literal("prop"))), new Vector3d(), pose);
        final BlockStructurePropeller propeller = new BlockStructurePropeller() {
            @Override
            public double thrustNewtons(StructurePhysicsContext context) {
                return 250.0;
            }

            @Override
            public Vector3dc thrustAxisLocal() {
                return new Vector3d(1.0, 0.0, 0.0);
            }
        };

        propeller.structurePhysicsTick(context);

        final Vector3d force = context.group.accumulatedForce(new Vector3d());
        assertEquals(0.0, force.x, 1.0e-6);
        assertEquals(250.0, force.y, 1.0e-6);
        assertEquals(0.0, force.z, 1.0e-6);
    }

    private static final class TestContext implements StructurePhysicsContext {
        private final QueuedForceGroup group;
        private final Vector3d centerOfMass;
        private final Transform3d pose;

        private TestContext(QueuedForceGroup group, Vector3d centerOfMass, Transform3d pose) {
            this.group = group;
            this.centerOfMass = centerOfMass;
            this.pose = pose;
        }

        @Override
        public PhysicsStructure structure() {
            return null;
        }

        @Override
        public ServerLevel level() {
            return null;
        }

        @Override
        public Transform3d structurePose() {
            return pose;
        }

        @Override
        public RigidBodyHandle body() {
            return null;
        }

        @Override
        public MassData massData() {
            return null;
        }

        @Override
        public long bodyId() {
            return 0L;
        }

        @Override
        public Vector3dc centerOfMassWorld() {
            return centerOfMass;
        }

        @Override
        public QueuedForceGroup forceGroup(ForceGroup group) {
            return this.group;
        }
    }
}
