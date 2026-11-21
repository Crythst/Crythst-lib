package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.integration.minecraft.entity.EntityPhysicsCollisionManager;
import io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingManager;
import io.mikaple.crythst.integration.create.CreateCompat;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Wires {@link MinecraftPhysics} into the Fabric server lifecycle.
 */
public final class PhysicsTicker {
    private PhysicsTicker() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(MinecraftPhysics::tick);
        // Registered after the physics tick so entity correction sees the pose
        // produced by the current step.
        EntityPhysicsCollisionManager.register();
        // Create contraption entities are carried by their own movement/riding
        // layer; do not also push them around as ordinary entities.
        EntityPhysicsCollisionManager.setEntityFilter(entity -> !CreateCompat.shouldStayOnStructure(entity));
        EntityStructureRidingManager.register();
        ServerLevelEvents.UNLOAD.register((server, level) -> MinecraftPhysics.unload(level));
    }
}
