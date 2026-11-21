package io.mikaple.crythst.api.event;

import io.mikaple.crythst.api.physics.PhysicsPipeline;
import net.minecraft.server.level.ServerLevel;

/**
 * Fired once when a server level's physics world and structure container have
 * been created and are ready to accept bodies.
 *
 * <p>The event runs on the server thread. It is safe to spawn or restore
 * structures here, but the level's physics world may still have no registered
 * structures at that point.</p>
 *
 * @param level   level whose physics world was created
 * @param physics API view of that world
 */
public record StructureContainerReadyEvent(ServerLevel level, PhysicsPipeline physics) {
    public StructureContainerReadyEvent {
        if (level == null) {
            throw new NullPointerException("level");
        }
        if (physics == null) {
            throw new NullPointerException("physics");
        }
    }
}
