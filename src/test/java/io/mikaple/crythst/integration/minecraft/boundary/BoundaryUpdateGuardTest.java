package io.mikaple.crythst.integration.minecraft.boundary;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryUpdateGuardTest {
    @Test
    void nestedDispatchDepthsUnwindCleanly() {
        final BoundaryUpdateGuard guard = new BoundaryUpdateGuard();
        assertTrue(guard.beginStructureToHost(7L));
        assertTrue(guard.beginStructureToHost(9L));
        assertEquals(2, guard.structureToHostDepth());
        assertEquals(9L, guard.currentOriginStructureId());
        guard.endStructureToHost();
        assertEquals(7L, guard.currentOriginStructureId());
        guard.endStructureToHost();
        assertFalse(guard.isStructureToHostDispatch());
        assertEquals(-1L, guard.currentOriginStructureId());
    }

    @Test
    void dispatchDepthIsCapped() {
        final BoundaryUpdateGuard guard = new BoundaryUpdateGuard();
        for (int i = 0; i < BoundaryUpdateGuard.MAX_DISPATCH_DEPTH; i++) {
            assertTrue(guard.beginStructureToHost(i));
        }
        assertFalse(guard.beginStructureToHost(999L),
                "an unbounded structure chain must be capped");
        assertTrue(guard.droppedDepth() > 0);
    }

    @Test
    void signalRecursionIsCapped() {
        final BoundaryUpdateGuard guard = new BoundaryUpdateGuard();
        for (int i = 0; i < BoundaryUpdateGuard.MAX_SIGNAL_DEPTH; i++) {
            assertTrue(guard.beginSignal());
        }
        assertFalse(guard.beginSignal());
        guard.endSignal();
        assertTrue(guard.beginSignal());
    }

    @Test
    void hostToStructureDispatchSuppressesTheReturnPath() {
        final BoundaryUpdateGuard guard = new BoundaryUpdateGuard();
        guard.beginHostToStructure();
        assertTrue(guard.isHostToStructureDispatch());
        guard.endHostToStructure();
        assertFalse(guard.isHostToStructureDispatch());
    }

    @Test
    void resetPerTickFailsClosedIfADepthWasLeftOpen() {
        final BoundaryUpdateGuard guard = new BoundaryUpdateGuard();
        guard.beginHostToStructure();
        guard.resetPerTick();
        assertFalse(guard.isHostToStructureDispatch());
        assertTrue(guard.droppedDepth() > 0);
    }

    @Test
    void budgetAndDedupeHelpersBehave() {
        final BoundaryWorkBudget budget = new BoundaryWorkBudget(2);
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertFalse(budget.tryConsume());
        assertEquals(2, budget.used());
        budget.reset();
        assertEquals(2, budget.remaining());

        final Set<Long> seen = new HashSet<>();
        assertTrue(BoundaryUpdateGuard.addOnce(seen, 4L));
        assertFalse(BoundaryUpdateGuard.addOnce(seen, 4L));
        assertTrue(BoundaryUpdateGuard.addOnce(seen, 5L));
    }
}
