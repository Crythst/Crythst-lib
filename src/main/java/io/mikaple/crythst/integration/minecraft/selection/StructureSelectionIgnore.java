package io.mikaple.crythst.integration.minecraft.selection;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-local set of structures that the current selection raycast must
 * ignore.
 *
 * <p>This is the Crythst equivalent of Sable's {@code ClipContextExtension}:
 * a caller can hide one structure (or every structure) for the duration of a
 * {@code ClientLevel.clip} without changing the world, the entity or the
 * physics state. Real blocks are unaffected and keep their normal precedence,
 * because {@link StructureSelection#clip} still returns the nearer vanilla hit
 * when it wins.</p>
 *
 * <p>The set is thread-local so a render-thread raycast cannot suppress a
 * structure for a server-thread interaction. Scopes nest: ignoring the same
 * body id twice requires closing both handles. Handles are idempotent, but
 * should be closed on the thread that created them.</p>
 */
public final class StructureSelectionIgnore {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private StructureSelectionIgnore() {
    }

    /**
     * Ignores one structure until the returned handle is closed.
     *
     * @throws IllegalArgumentException when {@code bodyId} is negative
     */
    public static AutoCloseable ignore(long bodyId) {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        final State state = STATE.get();
        state.ignored.merge(bodyId, 1, Integer::sum);
        return new Handle(() -> release(bodyId));
    }

    /** Ignores every structure until the returned handle is closed. */
    public static AutoCloseable ignoreAll() {
        final State state = STATE.get();
        state.ignoreAllDepth++;
        return new Handle(StructureSelectionIgnore::releaseAll);
    }

    /** Whether the current thread is ignoring at least one structure. */
    public static boolean active() {
        final State state = STATE.get();
        return state.ignoreAllDepth > 0 || !state.ignored.isEmpty();
    }

    /** Whether {@code bodyId} is currently ignored on this thread. */
    public static boolean isIgnored(long bodyId) {
        final State state = STATE.get();
        return state.ignoreAllDepth > 0 || state.ignored.containsKey(bodyId);
    }

    /** Whether every structure is currently ignored on this thread. */
    public static boolean ignoresAll() {
        return STATE.get().ignoreAllDepth > 0;
    }

    /** Drops all ignores for the current thread, regardless of open handles. */
    public static void clear() {
        STATE.remove();
    }

    /**
     * Filters a candidate list according to the current thread's ignores.
     * Returns the original iterable when no ignore is active.
     */
    public static Iterable<? extends StructureVoxelView> filterIfActive(
            @Nullable Iterable<? extends StructureVoxelView> views) {
        if (views == null || !active()) {
            return views;
        }
        final List<StructureVoxelView> filtered = new ArrayList<>();
        for (StructureVoxelView view : views) {
            if (view != null && !isIgnored(view.bodyId())) {
                filtered.add(view);
            }
        }
        return filtered;
    }

    private static void release(long bodyId) {
        final State state = STATE.get();
        final Integer count = state.ignored.get(bodyId);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            state.ignored.remove(bodyId);
        } else {
            state.ignored.put(bodyId, count - 1);
        }
        cleanup(state);
    }

    private static void releaseAll() {
        final State state = STATE.get();
        if (state.ignoreAllDepth > 0) {
            state.ignoreAllDepth--;
        }
        cleanup(state);
    }

    private static void cleanup(State state) {
        if (state.ignoreAllDepth == 0 && state.ignored.isEmpty()) {
            // Do not retain an empty map for long-lived render threads.
            STATE.remove();
        }
    }

    private static final class State {
        private final Map<Long, Integer> ignored = new HashMap<>(4);
        private int ignoreAllDepth;
    }

    private static final class Handle implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
