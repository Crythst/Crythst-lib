package io.mikaple.crythst.integration.fabric;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.buoyancy.BuoyancyManager;
import io.mikaple.crythst.integration.minecraft.interaction.StructureInteractionEvents;
import io.mikaple.crythst.integration.minecraft.mass.BlockMassReload;
import io.mikaple.crythst.integration.minecraft.storage.StructurePersistence;
import io.mikaple.crythst.integration.create.CreateCompat;
import io.mikaple.crythst.integration.fabric.network.StructureSync;
import io.mikaple.crythst.integration.portal.PortalManager;
import io.mikaple.crythst.integration.minecraft.PhysicsTicker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Common Fabric entrypoint.
 */
public final class CrythstFabric implements ModInitializer {
    private static CrythstConfig config = new CrythstConfig();

    public static CrythstConfig config() {
        return config;
    }

    @Override
    public void onInitialize() {
        config = CrythstConfig.load(FabricLoader.getInstance().getConfigDir().resolve("crythst.json"));
        MinecraftPhysics.init(config);
        BlockMassReload.register();
        BuoyancyManager.register();
        StructureInteractionEvents.registerCommon();
        StructurePersistence.register();
        CreateCompat.register();
        StructureSync.registerCommon();

        CommandRegistrationCallback.EVENT.register(CrythstCommand::register);
        // Physics first: the portal teleport pass reads the poses/velocities
        // produced by the current physics tick and must run after it.
        PhysicsTicker.register();
        PortalManager.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MinecraftPhysics.closeAll());

        Crythst.LOGGER.info("Crythst initialised (backend available: {})", MinecraftPhysics.available());
    }
}
