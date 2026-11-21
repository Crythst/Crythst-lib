package io.mikaple.crythst.integration.minecraft.mass;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMassMapperTest {
    private static final double EPSILON = 1.0e-9;

    private static CrythstConfig config() {
        final CrythstConfig config = new CrythstConfig();
        config.defaultBlockMass = 1234.0;
        config.defaultFriction = 0.55;
        config.defaultRestitution = 0.25;
        config.endStoneBlockMass = 500.0;
        return config;
    }

    private static BlockPhysicsRegistry registry(CrythstConfig config) {
        return new BlockPhysicsRegistry(BlockPhysicsProfile.fromConfig(config));
    }

    @Test
    void endStoneKeepsBuiltInBuoyantFallbackWithoutData() {
        final CrythstConfig config = config();
        final BlockPhysicsRegistry registry = registry(config);

        final BlockPhysicsMapper.BlockProperties properties =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:end_stone", Set.of(), false);

        assertEquals(500.0, properties.mass(), EPSILON);
        assertEquals(PhysicsMaterial.STONE, properties.material());
    }

    @Test
    void endStoneFallbackSurvivesDataDefaultsButYieldsToExplicitEntry() {
        final CrythstConfig config = config();
        final BlockPhysicsRegistry registry = registry(config);
        registry.applyJson("{\"defaults\":{\"mass\":2500}}");

        final BlockPhysicsMapper.BlockProperties fallback =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:end_stone", Set.of(), false);
        assertEquals(500.0, fallback.mass(), EPSILON);
        assertEquals(PhysicsMaterial.STONE, fallback.material());

        registry.applyJson("""
                { "blocks": { "minecraft:end_stone": {
                    "mass": 650, "friction": 0.3, "buoyancyScale": 1.5 } } }
                """);
        final BlockPhysicsMapper.BlockProperties explicit =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:end_stone", Set.of(), false);
        assertEquals(650.0, explicit.mass(), EPSILON);
        assertEquals(0.3, explicit.material().friction(), EPSILON);
        assertEquals(650.0, explicit.material().density(), EPSILON);
        assertEquals(1.5, registry.profile("minecraft:end_stone", Set.of()).buoyancyScale(), EPSILON);
    }

    @Test
    void configFallbackKeepsLegacySurfaceHeuristic() {
        final CrythstConfig config = config();
        final BlockPhysicsRegistry registry = registry(config);

        final BlockPhysicsMapper.BlockProperties generic =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:obsidian", Set.of(), false);
        assertEquals(1234.0, generic.mass(), EPSILON);
        assertEquals(0.55, generic.material().friction(), EPSILON);
        assertEquals(0.25, generic.material().restitution(), EPSILON);
        // Density stays mass/1000 for the data-free compatibility path.
        assertEquals(1.234, generic.material().density(), EPSILON);

        final BlockPhysicsMapper.BlockProperties planks =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:oak_planks", Set.of(), false);
        assertEquals(PhysicsMaterial.WOOD, planks.material());

        final BlockPhysicsMapper.BlockProperties ice =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:blue_ice", Set.of(), false);
        assertEquals(PhysicsMaterial.ICE, ice.material());
    }

    @Test
    void dataPackTagOverridesSurfaceAndMass() {
        final CrythstConfig config = config();
        final BlockPhysicsRegistry registry = registry(config);
        registry.applyJson("""
                { "tags": { "minecraft:planks": { "mass": 700, "friction": 0.6 } } }
                """);

        final BlockPhysicsMapper.BlockProperties properties = BlockPhysicsMapper.propertiesFor(
                registry, config, "minecraft:oak_planks", Set.of("minecraft:planks"), false);

        assertEquals(700.0, properties.mass(), EPSILON);
        assertEquals(0.6, properties.material().friction(), EPSILON);
        assertEquals(700.0, properties.material().density(), EPSILON);
    }

    @Test
    void dataDefaultsReplaceConfigFallbackForUnlistedBlocks() {
        final CrythstConfig config = config();
        final BlockPhysicsRegistry registry = registry(config);
        registry.applyJson("{\"defaults\":{\"mass\":2500,\"friction\":0.2}}");

        final BlockPhysicsMapper.BlockProperties properties =
                BlockPhysicsMapper.propertiesFor(registry, config, "minecraft:obsidian", Set.of(), false);

        assertEquals(2500.0, properties.mass(), EPSILON);
        assertEquals(0.2, properties.material().friction(), EPSILON);
        assertEquals(2500.0, properties.material().density(), EPSILON);
    }

    @Test
    void airHasZeroMass() {
        final CrythstConfig config = config();
        final BlockPhysicsMapper.BlockProperties properties =
                BlockPhysicsMapper.propertiesFor(registry(config), config, "minecraft:air", Set.of(), true);

        assertEquals(0.0, properties.mass(), EPSILON);
        assertEquals(PhysicsMaterial.DEFAULT, properties.material());
    }

    @Test
    void profileFromConfigCopiesConfiguredDefaults() {
        final CrythstConfig config = config();
        final BlockPhysicsProfile profile = BlockPhysicsProfile.fromConfig(config);

        assertEquals(1234.0, profile.mass(), EPSILON);
        assertEquals(0.55, profile.friction(), EPSILON);
        assertEquals(0.25, profile.restitution(), EPSILON);
        assertEquals(0.0, profile.rollingFriction(), EPSILON);
        assertEquals(1.0, profile.buoyancyScale(), EPSILON);
        assertTrue(profile.toMaterial().density() > 0.0);
    }
}
