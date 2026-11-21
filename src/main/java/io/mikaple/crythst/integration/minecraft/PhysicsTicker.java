package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.integration.minecraft.entity.EntityPhysicsCollisionManager;
import io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingManager;
import io.mikaple.crythst.integration.create.CreateCompat;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires {@link MinecraftPhysics} into the Fabric server lifecycle.
 */
public final class PhysicsTicker {
    private PhysicsTicker() {
    }

    private static final Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> DEMO_SPAWNED =
            ConcurrentHashMap.newKeySet();

    public static void resetDemoFlags() {
        DEMO_SPAWNED.clear();
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
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!MinecraftPhysics.config().demoOnJoin) {
                return;
            }
            final var level = handler.player.level();
            if (DEMO_SPAWNED.add(level.dimension())) {
                DemoSpawner.spawn(MinecraftPhysics.get(level), handler.player.position());
            }
        });
    }
}
