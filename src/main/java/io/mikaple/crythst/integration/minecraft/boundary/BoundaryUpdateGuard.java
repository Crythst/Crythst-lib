package io.mikaple.crythst.integration.minecraft.boundary;

import java.util.ArrayDeque;
import java.util.Set;

/**
 * Re-entrancy suppression for cross-boundary dispatch.
 *
 * <p>A host neighbour update must be delivered to the structure copy once, and
 * the structure copy must deliver its resulting neighbour update back to the
 * host once. Without a guard the two paths would keep waking each other:
 * host -&gt; structure -&gt; host -&gt; structure ... The guard marks the direction
 * that is currently dispatching, remembers which structure originated a
 * structure-&gt;host pass (so a two-structure chain does not immediately bounce
 * back to the sender), and caps recursive redstone signal evaluation.</p>
 *
 * <p>Server-thread only. The class is deliberately free of Minecraft types so
 * its nesting and depth behaviour is unit-testable.</p>
 */
public final class BoundaryUpdateGuard {
    /** Maximum nested cross-structure dispatches before work is dropped. */
    public static final int MAX_DISPATCH_DEPTH = 24;
    /** Maximum nested signal evaluation (A wire -&gt; B wire -&gt; A wire ...). */
    public static final int MAX_SIGNAL_DEPTH = 32;

    private int hostToStructureDepth;
    private int structureToHostDepth;
    private int signalDepth;
    private int gameEventToStructureDepth;
    private int gameEventToHostDepth;
    private int droppedDepth;
    private final ArrayDeque<Long> structureOrigins = new ArrayDeque<>();

    public void beginHostToStructure() {
        hostToStructureDepth++;
    }

    public void endHostToStructure() {
        if (hostToStructureDepth > 0) {
            hostToStructureDepth--;
        }
    }

    public boolean isHostToStructureDispatch() {
        return hostToStructureDepth > 0;
    }

    /** @return whether the dispatch was allowed, {@code false} when capped */
    public boolean beginStructureToHost(long structureId) {
        if (structureToHostDepth >= MAX_DISPATCH_DEPTH) {
            droppedDepth++;
            return false;
        }
        structureToHostDepth++;
        structureOrigins.push(structureId);
        return true;
    }

    public void endStructureToHost() {
        if (structureToHostDepth > 0) {
            structureToHostDepth--;
        }
        if (!structureOrigins.isEmpty()) {
            structureOrigins.pop();
        }
    }

    public boolean isStructureToHostDispatch() {
        return structureToHostDepth > 0;
    }

    /** Structure currently being forwarded to the host, or {@code -1}. */
    public long currentOriginStructureId() {
        final Long id = structureOrigins.peek();
        return id == null ? -1L : id;
    }

    public int structureToHostDepth() {
        return structureToHostDepth;
    }

    /** @return whether a nested signal query is still allowed */
    public boolean beginSignal() {
        if (signalDepth >= MAX_SIGNAL_DEPTH) {
            droppedDepth++;
            return false;
        }
        signalDepth++;
        return true;
    }

    public void endSignal() {
        if (signalDepth > 0) {
            signalDepth--;
        }
    }

    public int signalDepth() {
        return signalDepth;
    }

    public void beginGameEventToStructure() {
        gameEventToStructureDepth++;
    }

    public void endGameEventToStructure() {
        if (gameEventToStructureDepth > 0) {
            gameEventToStructureDepth--;
        }
    }

    public boolean isGameEventToStructureDispatch() {
        return gameEventToStructureDepth > 0;
    }

    public void beginGameEventToHost() {
        gameEventToHostDepth++;
    }

    public void endGameEventToHost() {
        if (gameEventToHostDepth > 0) {
            gameEventToHostDepth--;
        }
    }

    public boolean isGameEventToHostDispatch() {
        return gameEventToHostDepth > 0;
    }

    /** Number of dispatches dropped because a depth cap was reached. */
    public int droppedDepth() {
        return droppedDepth;
    }

    /**
     * Per-tick dedupe helper. A boundary face can be touched by several
     * neighbour updates in one tick; delivering the same notification twice
     * makes repeaters/observers schedule duplicate ticks.
     */
    public static boolean addOnce(Set<Long> seen, long key) {
        return seen.add(key);
    }

    public void resetPerTick() {
        // Depths must be zero between ticks. Do not silently clear a non-zero
        // depth: that would hide an unmatched begin/end in a caller.
        if (hostToStructureDepth != 0 || structureToHostDepth != 0
                || signalDepth != 0 || gameEventToStructureDepth != 0
                || gameEventToHostDepth != 0) {
            droppedDepth++;
            hostToStructureDepth = 0;
            structureToHostDepth = 0;
            signalDepth = 0;
            gameEventToStructureDepth = 0;
            gameEventToHostDepth = 0;
            structureOrigins.clear();
        }
    }

    public void clear() {
        hostToStructureDepth = 0;
        structureToHostDepth = 0;
        signalDepth = 0;
        gameEventToStructureDepth = 0;
        gameEventToHostDepth = 0;
        structureOrigins.clear();
    }
}
