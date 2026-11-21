package io.mikaple.crythst.api.block;

import io.mikaple.crythst.api.physics.MassData;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.QueuedForceGroup;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureActorDispatchTest {
    @Test
    void invokesEveryActorInOrderAndIsolatesFailures() {
        final List<String> calls = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final BlockEntityStructureActor first = context -> calls.add("first");
        final BlockEntityStructureActor failing = context -> {
            calls.add("failing");
            throw new IllegalStateException("boom");
        };
        final BlockEntityStructureActor last = context -> calls.add("last");

        StructureActorDispatch.dispatch(List.of(first, failing, last), new FakeContext(), failures::add);

        assertEquals(List.of("first", "failing", "last"), calls);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof IllegalStateException);
    }

    @Test
    void aFailingFailureHandlerDoesNotStopLaterActors() {
        final List<String> calls = new ArrayList<>();
        final BlockEntityStructureActor failing = context -> {
            calls.add("failing");
            throw new IllegalStateException("boom");
        };
        final BlockEntityStructureActor last = context -> calls.add("last");

        StructureActorDispatch.dispatch(List.of(failing, last), new FakeContext(), failure -> {
            throw new IllegalStateException("handler");
        });

        assertEquals(List.of("failing", "last"), calls);
    }

    private static final class FakeContext implements StructurePhysicsContext {
        private final Vector3d center = new Vector3d();

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
            return Transform3d.IDENTITY;
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
            return center;
        }

        @Override
        public QueuedForceGroup forceGroup(ForceGroup group) {
            return null;
        }
    }
}
