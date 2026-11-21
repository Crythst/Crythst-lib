package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StructureContainerEventsTest {
    @AfterEach
    void clear() {
        StructureContainerEvents.clear();
    }

    @Test
    void readyHandleReceivesTheContainerAndCanBeRemoved() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final FakeContainer container = new FakeContainer();
        final AutoCloseable handle = StructureContainerEvents.registerContainerReady(received -> {
            calls.incrementAndGet();
            assertSame(container, received);
        });

        StructureContainerEvents.fireContainerReady(container);
        assertEquals(1, calls.get());
        assertEquals(1, StructureContainerEvents.readyListenerCount());

        handle.close();
        StructureContainerEvents.fireContainerReady(container);
        assertEquals(1, calls.get());
        assertEquals(0, StructureContainerEvents.readyListenerCount());
    }

    @Test
    void globalObserverCanBeRegisteredAndRemoved() throws Exception {
        final StructureObserver observer = new StructureObserver() {
            @Override
            public void onStructureAdded(PhysicsStructure structure) {
            }

            @Override
            public void onStructureRemoved(PhysicsStructure structure) {
            }

            @Override
            public void onStructureRefreshed(PhysicsStructure structure) {
            }
        };

        final AutoCloseable handle = StructureContainerEvents.registerObserver(observer);
        assertEquals(1, StructureContainerEvents.observerCount());

        handle.close();
        assertEquals(0, StructureContainerEvents.observerCount());
    }

    private static final class FakeContainer implements StructureContainer {
        @Override
        public Level level() {
            return null;
        }

        @Override
        public List<PhysicsStructure> structures() {
            return List.of();
        }
    }
}
