package io.mikaple.crythst.api.event;

import io.mikaple.crythst.api.physics.PhysicsPipeline;
import net.minecraft.server.level.ServerLevel;

/**
 * One fixed physics substep.
 *
 * <p>Crythst advances physics with a fixed clock. A single server tick can run
 * zero, one or several substeps, so listeners receive this event once per
 * substep, not once per game tick. The same event object shape is used by the
 * pre- and post-step callbacks; {@code tickIndex} increases monotonically for
 * the lifetime of the level's physics world and is shared by both callbacks.</p>
 *
 * <p>Always delivered on the server thread. Do not retain the event object
 * after the callback returns.</p>
 *
 * @param level           server level being simulated
 * @param physics         API view of the level's physics pipeline
 * @param timeStepSeconds fixed substep length in seconds (normally 1/60 or
 *                        1/120 depending on configuration)
 * @param tickIndex       zero-based substep index for this physics world
 */
public record PhysicsTickEvent(
        ServerLevel level,
        PhysicsPipeline physics,
        double timeStepSeconds,
        long tickIndex
) {
    public PhysicsTickEvent {
        if (level == null) {
            throw new NullPointerException("level");
        }
        if (physics == null) {
            throw new NullPointerException("physics");
        }
        if (!Double.isFinite(timeStepSeconds) || timeStepSeconds <= 0.0) {
            throw new IllegalArgumentException("timeStepSeconds must be positive: " + timeStepSeconds);
        }
        if (tickIndex < 0L) {
            throw new IllegalArgumentException("tickIndex must not be negative");
        }
    }
}
