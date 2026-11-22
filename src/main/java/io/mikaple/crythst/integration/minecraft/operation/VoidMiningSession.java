package io.mikaple.crythst.integration.minecraft.operation;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pure server-side state machine for one held mining session against a structure
 * voxel that is mirrored into the auxiliary void level.
 *
 * <p>The server owns progress; the client only sends START/TICK/STOP. A START
 * creates (or refreshes) the session. While the active target, tool and
 * heartbeat stay valid, the server advances one tick of vanilla
 * {@code BlockState#getDestroyProgress} progress per game tick. Releasing the
 * attack key, losing the target, changing the tool or missing heartbeats for
 * {@link #HEARTBEAT_TIMEOUT_TICKS} aborts the session. Reaching {@code 1.0}
 * completes it, and the caller runs vanilla {@code destroyBlock} exactly once.</p>
 *
 * <p>This class deliberately knows nothing about Minecraft so the long-hold,
 * creative, timeout and no-double-break behaviour can be unit tested without a
 * server.</p>
 */
public final class VoidMiningSession {
    /** How many game ticks the server may go without a client heartbeat. */
    public static final int HEARTBEAT_TIMEOUT_TICKS = 4;

    /** Lifecycle of a session. */
    public enum Phase {
        IDLE,
        ACTIVE,
        COMPLETE,
        ABORTED
    }

    /** Result of a START/heartbeat/tick transition. */
    public enum Step {
        /** No state change. */
        NONE,
        /** A new survival session started at zero progress. */
        STARTED,
        /** Progress advanced but the target is not broken yet. */
        PROGRESSING,
        /** The target completed; the caller must break it exactly once. */
        BREAK,
        /** No heartbeat arrived in time. */
        EXPIRED,
        /** The target or tool changed, or the session was stopped. */
        ABORTED
    }

    /**
     * Stable identity of one structure voxel. Block-state identity is checked
     * separately by the server wrapper through the live auxiliary block.
     */
    public record Target(long bodyId, int x, int y, int z) {
    }

    private @Nullable Target target;
    private @Nullable Object tool;
    private Phase phase = Phase.IDLE;
    private float progress;
    private boolean creative;
    private long startTick;
    private long lastHeartbeatTick;
    private long lastAdvanceTick;

    /**
     * Starts a session or refreshes an existing identical one.
     *
     * <p>Repeated START packets for the same target/tool/heartbeat do not reset
     * progress, so the attack callback and the explicit START payload can both
     * arrive without making mining slower. A different target, tool or creative
     * flag restarts at zero. Creative completes immediately.</p>
     */
    public Step start(long now, @Nullable Target target, @Nullable Object tool, boolean creative) {
        if (target == null) {
            abort();
            return Step.ABORTED;
        }
        if ((phase == Phase.ACTIVE || phase == Phase.COMPLETE)
                && target.equals(this.target)
                && Objects.equals(tool, this.tool)
                && creative == this.creative) {
            // A duplicate START (attack callback + payload) must not restart an
            // active session, and a completed target must not break twice.
            lastHeartbeatTick = now;
            return Step.NONE;
        }

        this.target = target;
        this.tool = tool;
        this.creative = creative;
        this.progress = 0.0F;
        this.startTick = now;
        this.lastHeartbeatTick = now;
        this.lastAdvanceTick = now - 1L;
        if (creative) {
            progress = 1.0F;
            phase = Phase.COMPLETE;
            return Step.BREAK;
        }
        phase = Phase.ACTIVE;
        return Step.STARTED;
    }

    /**
     * Refreshes the heartbeat and verifies that the client is still mining the
     * same target with the same tool.
     */
    public Step heartbeat(long now, @Nullable Target target, @Nullable Object tool) {
        if (phase != Phase.ACTIVE) {
            return Step.NONE;
        }
        if (target == null || !target.equals(this.target) || !Objects.equals(tool, this.tool)) {
            abort();
            return Step.ABORTED;
        }
        lastHeartbeatTick = now;
        return Step.NONE;
    }

    /**
     * Checks the heartbeat without advancing progress. Used before ticking the
     * vanilla game mode so an expired session never receives another tick.
     */
    public Step checkHeartbeat(long now) {
        if (phase != Phase.ACTIVE) {
            return Step.NONE;
        }
        if (now - lastHeartbeatTick > HEARTBEAT_TIMEOUT_TICKS) {
            abort();
            return Step.EXPIRED;
        }
        return Step.NONE;
    }

    /**
     * Advances one server tick of progress.
     *
     * @param perTick vanilla {@code BlockState#getDestroyProgress} for this tick
     */
    public Step tick(long now, float perTick) {
        if (phase != Phase.ACTIVE) {
            return Step.NONE;
        }
        if (now - lastHeartbeatTick > HEARTBEAT_TIMEOUT_TICKS) {
            abort();
            return Step.EXPIRED;
        }
        if (now <= lastAdvanceTick) {
            return Step.NONE;
        }
        lastAdvanceTick = now;
        if (!(perTick > 0.0F)) {
            // Unbreakable block or no progress. Keep the session alive until
            // release/timeout, but do not complete it.
            return Step.NONE;
        }
        if (!Float.isFinite(perTick) || progress + perTick >= 1.0F) {
            progress = 1.0F;
            phase = Phase.COMPLETE;
            return Step.BREAK;
        }
        progress = Math.max(progress, Math.min(1.0F, progress + perTick));
        return Step.PROGRESSING;
    }

    /** Stops an active session. A completed session stays completed. */
    public Step stop() {
        if (phase != Phase.ACTIVE) {
            return Step.NONE;
        }
        phase = Phase.ABORTED;
        return Step.ABORTED;
    }

    /** Marks the session aborted without any transition result. */
    public void abort() {
        if (phase == Phase.ACTIVE || phase == Phase.IDLE) {
            phase = Phase.ABORTED;
        }
    }

    public Phase phase() {
        return phase;
    }

    public boolean active() {
        return phase == Phase.ACTIVE;
    }

    public boolean completed() {
        return phase == Phase.COMPLETE;
    }

    public float progress() {
        return progress;
    }

    public long startTick() {
        return startTick;
    }

    public boolean creative() {
        return creative;
    }

    public @Nullable Target target() {
        return target;
    }
}
