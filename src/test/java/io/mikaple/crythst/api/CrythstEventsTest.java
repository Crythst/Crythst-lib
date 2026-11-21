package io.mikaple.crythst.api;

import io.mikaple.crythst.api.event.PostPhysicsTick;
import io.mikaple.crythst.api.event.PrePhysicsTick;
import io.mikaple.crythst.api.event.StructureContainerReady;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrythstEventsTest {
    @AfterEach
    void clear() {
        CrythstEvents.clear();
    }

    @Test
    void registrationHandlesRemoveOnlyTheirOwnListener() throws Exception {
        final AtomicInteger pre = new AtomicInteger();
        final PrePhysicsTick first = event -> pre.incrementAndGet();
        final PrePhysicsTick second = event -> pre.incrementAndGet();

        final AutoCloseable firstHandle = CrythstEvents.registerPrePhysicsTick(first);
        CrythstEvents.registerPrePhysicsTick(second);
        assertEquals(2, CrythstEvents.prePhysicsTickListenerCount());

        firstHandle.close();
        assertEquals(1, CrythstEvents.prePhysicsTickListenerCount());
        CrythstEvents.unregisterPrePhysicsTick(second);
        assertEquals(0, CrythstEvents.prePhysicsTickListenerCount());
    }

    @Test
    void allListenerKindsHaveIndependentCounts() {
        final PrePhysicsTick pre = event -> {
        };
        final PostPhysicsTick post = event -> {
        };
        final StructureContainerReady ready = event -> {
        };

        CrythstEvents.registerPrePhysicsTick(pre);
        CrythstEvents.registerPostPhysicsTick(post);
        CrythstEvents.registerStructureContainerReady(ready);

        assertEquals(1, CrythstEvents.prePhysicsTickListenerCount());
        assertEquals(1, CrythstEvents.postPhysicsTickListenerCount());
        assertEquals(1, CrythstEvents.structureContainerReadyListenerCount());
    }
}
