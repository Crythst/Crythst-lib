package io.mikaple.crythst.integration.minecraft.interaction.client;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pure state machine for one held-attack mining session.
 *
 * <p>It deliberately knows nothing about Minecraft. The controller supplies a
 * stable target token (body id + structure voxel + block state) and a tool
 * token, so this class can be unit-tested without a client. Switching either
 * token starts a new session at zero progress; finishing a target marks the
 * session complete so the same stale target cannot send another break packet
 * until the crosshair actually moves to a different target (or the tool
 * changes).</p>
 */
public final class MiningProgress {
    public enum Step {
        /** Progress was accumulated but the target is not broken yet. */
        NONE,
        /** The target reached 1.0 (or creative mode was used). */
        BREAK
    }

    private @Nullable Object target;
    private @Nullable Object tool;
    private float progress;
    private boolean completed;

    /**
     * Advances one tick.
     *
     * @param target    stable target token, or {@code null} when the crosshair
     *                  is no longer on a structure
     * @param tool      stable tool token (the held {@code ItemStack} or an
     *                  equivalent identity)
     * @param perTick   progress for this tick from {@link MiningMath}
     * @param creative  whether the player has instant break
     * @return whether this tick completed the target
     */
    public Step tick(@Nullable Object target, @Nullable Object tool,
                     float perTick, boolean creative) {
        if (target == null) {
            reset();
            return Step.NONE;
        }
        if (!Objects.equals(this.target, target) || !Objects.equals(this.tool, tool)) {
            this.target = target;
            this.tool = tool;
            progress = 0.0F;
            completed = false;
        }
        if (completed) {
            // The server may not have removed the voxel yet. Do not send a
            // second MINE for the same target while the crosshair is stale.
            return Step.NONE;
        }
        if (creative) {
            completed = true;
            progress = 0.0F;
            return Step.BREAK;
        }
        progress = MiningMath.advance(progress, perTick);
        if (MiningMath.isComplete(progress)) {
            progress = 0.0F;
            completed = true;
            return Step.BREAK;
        }
        return Step.NONE;
    }

    /** Clears the target, tool, progress and completion flag. */
    public void reset() {
        target = null;
        tool = null;
        progress = 0.0F;
        completed = false;
    }

    public float progress() {
        return progress;
    }

    public boolean hasTarget() {
        return target != null;
    }

    /** Whether progress is visible for the current target. */
    public boolean isMining() {
        return target != null && !completed && progress > 0.0F;
    }

    /** Whether the current target was already completed and awaits a target change. */
    public boolean isCompleted() {
        return completed;
    }

    public int stage() {
        return completed ? -1 : MiningMath.stage(progress);
    }
}
