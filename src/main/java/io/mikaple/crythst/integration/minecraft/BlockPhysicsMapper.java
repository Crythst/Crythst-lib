package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsProfile;
import io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsRegistry;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps Minecraft block states to collision mass and surface material.
 *
 * <p>{@link #defaults(CrythstConfig)} is backed by the shared
 * {@link BlockPhysicsRegistry}, so a server data pack can override masses and
 * surface properties via {@code data/crythst/block_physics.json}. Precedence is
 * exact block, then block tag, then data defaults, then {@code CrythstConfig}.</p>
 *
 * <p>Gameplay mods can still install a completely custom mapper through
 * {@link MinecraftPhysics#setBlockPhysics}. Arbitrary gravity means there is no
 * hard-coded "down" here.</p>
 */
@FunctionalInterface
public interface BlockPhysicsMapper {
    String END_STONE_ID = "minecraft:end_stone";

    BlockProperties properties(BlockState state);

    record BlockProperties(double mass, PhysicsMaterial material) {
        public static BlockProperties of(double mass, PhysicsMaterial material) {
            return new BlockProperties(mass, material);
        }
    }

    /**
     * Registry-backed mapper with config fallbacks.
     *
     * <p>Also publishes the config values as the registry fallback, so a data
     * pack's {@code defaults} object only has to specify the fields it wants to
     * override.</p>
     */
    static BlockPhysicsMapper defaults(CrythstConfig config) {
        Validate.notNull(config, "config");
        final BlockPhysicsRegistry registry = BlockPhysicsRegistry.shared();
        registry.setFallbackDefaults(BlockPhysicsProfile.fromConfig(config));
        return state -> properties(registry, config, state);
    }

    /**
     * Maps a live block state. Tags are read and cached by the registry.
     */
    static BlockProperties properties(BlockPhysicsRegistry registry, CrythstConfig config, BlockState state) {
        Validate.notNull(registry, "registry");
        Validate.notNull(config, "config");
        Validate.notNull(state, "state");
        if (state.isAir()) {
            return new BlockProperties(0.0, PhysicsMaterial.DEFAULT);
        }
        final String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return fromResolution(registry, config, id, registry.resolve(state));
    }

    /**
     * Pure lookup used by tests and integrations that already have a block id
     * and its tags. It does not touch the Minecraft block registry.
     *
     * @param blockTags tag ids with or without a leading {@code #}
     * @param air       {@code true} for air/empty space (zero mass)
     */
    static BlockProperties propertiesFor(BlockPhysicsRegistry registry, CrythstConfig config,
                                         String blockId, Collection<String> blockTags, boolean air) {
        Validate.notNull(registry, "registry");
        Validate.notNull(config, "config");
        Validate.notNull(blockId, "blockId");
        if (air) {
            return new BlockProperties(0.0, PhysicsMaterial.DEFAULT);
        }
        return fromResolution(registry, config, blockId, registry.resolve(blockId, blockTags));
    }

    private static BlockProperties fromResolution(BlockPhysicsRegistry registry, CrythstConfig config,
                                                  String blockId, BlockPhysicsRegistry.Resolution resolution) {
        if (resolution.explicit()) {
            return toProperties(resolution.profile());
        }
        if (isEndStone(blockId)) {
            // Built-in buoyancy: end stone stays at the configured low mass even
            // when no data pack supplies an entry. Existing material choice is
            // preserved for API compatibility.
            return new BlockProperties(config.endStoneBlockMass, PhysicsMaterial.STONE);
        }
        final Optional<BlockPhysicsProfile> dataDefaults = registry.dataDefaults();
        if (dataDefaults.isPresent()) {
            return toProperties(dataDefaults.get());
        }
        return new BlockProperties(config.defaultBlockMass, legacyMaterial(config, blockId));
    }

    private static BlockProperties toProperties(BlockPhysicsProfile profile) {
        return new BlockProperties(profile.mass(), profile.toMaterial());
    }

    private static boolean isEndStone(String blockId) {
        return END_STONE_ID.equalsIgnoreCase(blockId.trim());
    }

    /**
     * Data-free material heuristic kept for existing worlds and custom mappers.
     * When a data pack is present its defaults take precedence over this.
     */
    private static PhysicsMaterial legacyMaterial(CrythstConfig config, String blockId) {
        final String name = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        if (name.contains("ice")) {
            return PhysicsMaterial.ICE;
        } else if (name.contains("plank") || name.contains("wood") || name.contains("log")) {
            return PhysicsMaterial.WOOD;
        } else if (name.contains("iron") || name.contains("copper") || name.contains("gold")
                || name.contains("steel")) {
            return PhysicsMaterial.METAL;
        } else if (name.contains("dirt") || name.contains("grass") || name.contains("sand")
                || name.contains("gravel")) {
            return PhysicsMaterial.DIRT;
        }
        return new PhysicsMaterial(config.defaultFriction, config.defaultRestitution, 0.0, 0.0,
                config.defaultBlockMass / 1000.0);
    }
}
