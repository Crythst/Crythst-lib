package io.mikaple.crythst.api.physics.force;

import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.physics.body.BodyHandle;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueuedForceGroupTest {
    private static final double EPSILON = 1.0e-9;

    private static ForceGroup group(String label) {
        return new ForceGroup(Component.literal(label));
    }

    @Test
    void pointForceThroughCenterOfMassProducesNoTorque() {
        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d(1.0, 2.0, 3.0));
        queued.applyPointForce(new Vector3d(1.0, 2.0, 3.0), new Vector3d(10.0, 0.0, 0.0));

        final Vector3d force = queued.accumulatedForce(new Vector3d());
        final Vector3d torque = queued.accumulatedTorque(new Vector3d());
        assertEquals(10.0, force.x, EPSILON);
        assertEquals(0.0, torque.length(), EPSILON);
    }

    @Test
    void pointForceOffsetProducesCrossProductTorque() {
        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d(1.0, 0.0, 0.0));
        // r = (0, 1, 0), F = (2, 0, 0), r x F = (0, 0, -2)
        queued.applyPointForce(new Vector3d(1.0, 1.0, 0.0), new Vector3d(2.0, 0.0, 0.0));

        final Vector3d torque = queued.accumulatedTorque(new Vector3d());
        assertEquals(0.0, torque.x, EPSILON);
        assertEquals(0.0, torque.y, EPSILON);
        assertEquals(-2.0, torque.z, EPSILON);
    }

    @Test
    void multiplePointForcesAccumulateLinearAndAngularParts() {
        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d());

        queued.applyPointForce(new Vector3d(0.0, 1.0, 0.0), new Vector3d(2.0, 0.0, 0.0));
        queued.applyPointForce(new Vector3d(0.0, 0.0, 1.0), new Vector3d(0.0, 3.0, 0.0));

        final Vector3d force = queued.accumulatedForce(new Vector3d());
        assertEquals(2.0, force.x, EPSILON);
        assertEquals(3.0, force.y, EPSILON);
        assertEquals(0.0, force.z, EPSILON);

        // (0,1,0)x(2,0,0) = (0,0,-2); (0,0,1)x(0,3,0) = (-3,0,0)
        final Vector3d torque = queued.accumulatedTorque(new Vector3d());
        assertEquals(-3.0, torque.x, EPSILON);
        assertEquals(0.0, torque.y, EPSILON);
        assertEquals(-2.0, torque.z, EPSILON);
    }

    @Test
    void linearAndAngularForcesAccumulate() {
        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d());

        queued.applyLinearAndAngularForce(new Vector3d(4.0, 0.0, 0.0), new Vector3d(0.0, 5.0, 0.0));
        queued.applyLinearAndAngularForce(new Vector3d(0.0, 6.0, 0.0), new Vector3d(0.0, 0.0, 7.0));

        final Vector3d force = queued.accumulatedForce(new Vector3d());
        assertEquals(4.0, force.x, EPSILON);
        assertEquals(6.0, force.y, EPSILON);

        final Vector3d torque = queued.accumulatedTorque(new Vector3d());
        assertEquals(5.0, torque.y, EPSILON);
        assertEquals(7.0, torque.z, EPSILON);
    }

    @Test
    void forceAndTorqueConvertToImpulsesUsingSubstepLength() {
        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d());
        queued.applyLinearAndAngularForce(new Vector3d(2.0, -4.0, 6.0), new Vector3d(8.0, -10.0, 12.0));

        final Vector3d impulse = queued.impulse(0.5, new Vector3d());
        assertEquals(1.0, impulse.x, EPSILON);
        assertEquals(-2.0, impulse.y, EPSILON);
        assertEquals(3.0, impulse.z, EPSILON);

        final Vector3d angularImpulse = queued.angularImpulse(0.25, new Vector3d());
        assertEquals(2.0, angularImpulse.x, EPSILON);
        assertEquals(-2.5, angularImpulse.y, EPSILON);
        assertEquals(3.0, angularImpulse.z, EPSILON);
    }

    @Test
    void resetClearsAccumulatorsAndUpdatesTheApplicationCenter() {
        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d(5.0, 5.0, 5.0));
        queued.applyPointForce(new Vector3d(5.0, 6.0, 5.0), new Vector3d(9.0, 0.0, 0.0));

        queued.reset(new Vector3d());
        assertEquals(0.0, queued.accumulatedForce(new Vector3d()).length(), EPSILON);
        assertEquals(0.0, queued.accumulatedTorque(new Vector3d()).length(), EPSILON);

        // A point force now measured from the new origin must not reuse the old
        // centre of mass.
        queued.applyPointForce(new Vector3d(0.0, 1.0, 0.0), new Vector3d(1.0, 0.0, 0.0));
        assertEquals(-1.0, queued.accumulatedTorque(new Vector3d()).z, EPSILON);
    }

    @Test
    void applyToConvertsToImpulseAndClearsTheAccumulators() {
        final AtomicReference<Vector3d> appliedLinear = new AtomicReference<>();
        final AtomicReference<Vector3d> appliedAngular = new AtomicReference<>();
        final BodyHandle fakeBody = (BodyHandle) Proxy.newProxyInstance(
                BodyHandle.class.getClassLoader(),
                new Class<?>[]{BodyHandle.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 1L;
                    case "applyCentralImpulse" -> {
                        appliedLinear.set(new Vector3d((Vector3dc) args[0]));
                        yield null;
                    }
                    case "applyTorqueImpulse" -> {
                        appliedAngular.set(new Vector3d((Vector3dc) args[0]));
                        yield null;
                    }
                    case "toString" -> "fake body";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });

        final QueuedForceGroup queued = new QueuedForceGroup(group("test"));
        queued.reset(new Vector3d());
        queued.applyLinearAndAngularForce(new Vector3d(2.0, -4.0, 6.0), new Vector3d(8.0, -10.0, 12.0));
        queued.applyTo(RigidBodyHandle.of(fakeBody), 0.5);

        assertEquals(1.0, appliedLinear.get().x, EPSILON);
        assertEquals(-2.0, appliedLinear.get().y, EPSILON);
        assertEquals(3.0, appliedLinear.get().z, EPSILON);
        assertEquals(4.0, appliedAngular.get().x, EPSILON);
        assertEquals(-5.0, appliedAngular.get().y, EPSILON);
        assertEquals(6.0, appliedAngular.get().z, EPSILON);
        assertEquals(0.0, queued.accumulatedForce(new Vector3d()).length(), EPSILON);
        assertEquals(0.0, queued.accumulatedTorque(new Vector3d()).length(), EPSILON);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
