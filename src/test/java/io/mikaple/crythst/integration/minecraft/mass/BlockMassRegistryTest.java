package io.mikaple.crythst.integration.minecraft.mass;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMassRegistryTest {
    private static final double EPSILON = 1.0e-9;

    private static final String SAMPLE = """
            {
              "defaults": { "mass": 2000, "friction": 0.5, "restitution": 0.1 },
              "blocks": {
                "minecraft:stone": { "mass": 2400, "friction": 0.7 },
                "minecraft:glass": { "friction": 0.3 },
                "minecraft:bad_mass": { "mass": -5 },
                "minecraft:bad_type": { "mass": "heavy" },
                "#minecraft:planks": { "mass": 700, "friction": 0.6, "restitution": 0.05 },
                "minecraft:weird": 42
              },
              "tags": {
                "#minecraft:logs": { "mass": 800 }
              }
            }
            """;

    private static BlockPhysicsRegistry registry(BlockPhysicsProfile fallback) {
        final BlockPhysicsRegistry registry = new BlockPhysicsRegistry(fallback);
        registry.applyJson(SAMPLE);
        return registry;
    }

    private static BlockPhysicsProfile fallback() {
        return new BlockPhysicsProfile(1000.0, 0.7, 0.0, 0.0, 0.0, 1.0);
    }

    @Test
    void exactBlockOverridesDefaultsAndTags() {
        final BlockPhysicsRegistry.Resolution resolution =
                registry(fallback()).resolve("minecraft:stone", List.of("minecraft:planks"));

        assertTrue(resolution.explicit());
        assertEquals(2400.0, resolution.profile().mass(), EPSILON);
        assertEquals(0.7, resolution.profile().friction(), EPSILON);
        // Missing fields inherit the data-pack defaults, not the config fallback.
        assertEquals(0.1, resolution.profile().restitution(), EPSILON);
    }

    @Test
    void tagEntriesInBlocksSectionAndTagsObjectBothResolve() {
        final BlockPhysicsRegistry registry = registry(fallback());

        final BlockPhysicsRegistry.Resolution planks =
                registry.resolve("minecraft:oak_planks", Set.of("minecraft:planks"));
        assertTrue(planks.explicit());
        assertEquals(700.0, planks.profile().mass(), EPSILON);
        assertEquals(0.05, planks.profile().restitution(), EPSILON);

        final BlockPhysicsRegistry.Resolution logs =
                registry.resolve("minecraft:oak_log", Set.of("#minecraft:logs"));
        assertTrue(logs.explicit());
        assertEquals(800.0, logs.profile().mass(), EPSILON);
        assertEquals(0.5, logs.profile().friction(), EPSILON);
    }

    @Test
    void firstConfiguredMatchingTagWinsDeterministically() {
        final BlockPhysicsRegistry registry = registry(fallback());

        // planks is declared before logs in the file, so a block carrying both
        // tags resolves through planks regardless of the set's iteration order.
        final BlockPhysicsRegistry.Resolution resolution =
                registry.resolve("minecraft:oak_planks", Set.of("minecraft:logs", "minecraft:planks"));

        assertEquals(700.0, resolution.profile().mass(), EPSILON);
        assertEquals(List.of("minecraft:planks", "minecraft:logs"), List.copyOf(registry.tagIds()));
    }

    @Test
    void unknownBlockUsesDataDefaults() {
        final BlockPhysicsRegistry.Resolution resolution =
                registry(fallback()).resolve("minecraft:obsidian", Set.of());

        assertFalse(resolution.explicit());
        assertEquals(2000.0, resolution.profile().mass(), EPSILON);
        assertEquals(0.5, resolution.profile().friction(), EPSILON);
        assertEquals(0.1, resolution.profile().restitution(), EPSILON);
        assertEquals(1.0, resolution.profile().buoyancyScale(), EPSILON);
    }

    @Test
    void missingAndInvalidFieldsFallBackPerField() {
        final BlockPhysicsRegistry registry = registry(fallback());

        final BlockPhysicsProfile glass = registry.profile("minecraft:glass", Set.of());
        assertEquals(2000.0, glass.mass(), EPSILON);
        assertEquals(0.3, glass.friction(), EPSILON);

        final BlockPhysicsProfile badMass = registry.profile("minecraft:bad_mass", Set.of());
        assertEquals(2000.0, badMass.mass(), EPSILON);

        final BlockPhysicsProfile badType = registry.profile("minecraft:bad_type", Set.of());
        assertEquals(2000.0, badType.mass(), EPSILON);

        // A non-object entry is skipped entirely, so it does not count as explicit.
        assertFalse(registry.resolve("minecraft:weird", Set.of()).explicit());
        assertEquals(2000.0, registry.profile("minecraft:weird", Set.of()).mass(), EPSILON);
    }

    @Test
    void resetRestoresConfigFallbackAndClearsEntries() {
        final BlockPhysicsRegistry registry = registry(fallback());
        assertEquals(2, registry.tagCount());

        registry.reset();

        assertEquals(0, registry.blockCount());
        assertEquals(0, registry.tagCount());
        assertTrue(registry.dataDefaults().isEmpty());
        assertEquals(1000.0, registry.profile("minecraft:stone", Set.of()).mass(), EPSILON);
    }

    @Test
    void missingResourceManagerFileRestoresDefaults() {
        final BlockPhysicsRegistry registry = registry(fallback());
        final boolean loaded = registry.load(new EmptyResourceManager());

        assertFalse(loaded);
        assertEquals(0, registry.blockCount());
        assertTrue(registry.dataDefaults().isEmpty());
        assertEquals(1000.0, registry.profile("minecraft:stone", Set.of()).mass(), EPSILON);
    }

    @Test
    void structurallyInvalidDocumentsAreRejected() {
        final BlockPhysicsRegistry registry = registry(fallback());

        assertThrows(IllegalArgumentException.class, () -> registry.applyJson("[]"));
        assertThrows(IllegalArgumentException.class, () -> registry.applyJson("{\"blocks\": []}"));
        assertThrows(IllegalArgumentException.class, () -> registry.applyJson("{\"tags\": 1}"));
        assertThrows(IllegalArgumentException.class, () -> registry.applyJson("{"));

        // The previous snapshot survives a failed reload.
        assertEquals(2400.0, registry.profile("minecraft:stone", Set.of()).mass(), EPSILON);
    }

    @Test
    void massIsUsedAsMaterialDensity() {
        final BlockPhysicsProfile profile = new BlockPhysicsProfile(2400.0, 0.7, 0.0, 0.02, 0.02, 1.0);
        final PhysicsMaterial material = profile.toMaterial();

        assertEquals(2400.0, material.density(), EPSILON);
        assertEquals(0.7, material.friction(), EPSILON);
        assertEquals(0.02, material.rollingFriction(), EPSILON);
        assertEquals(0.02, material.spinningFriction(), EPSILON);
    }

    @Test
    void profileConstructorRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockPhysicsProfile(0.0, 0.7, 0.0, 0.0, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockPhysicsProfile(-1.0, 0.7, 0.0, 0.0, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockPhysicsProfile(1000.0, -0.1, 0.0, 0.0, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockPhysicsProfile(1000.0, 0.7, 1.5, 0.0, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockPhysicsProfile(1000.0, 0.7, 0.0, 0.0, 0.0, -1.0));
    }

    @Test
    void shippedDataFileIsValidAndResolvesCommonBlocks() throws Exception {
        final String json;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("data/crythst/block_physics.json")) {
            assertNotNull(in, "shipped block physics data file");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        final BlockPhysicsRegistry registry = new BlockPhysicsRegistry(fallback());
        registry.applyJson(json);

        assertEquals(2400.0, registry.profile("minecraft:stone", Set.of()).mass(), EPSILON);
        assertEquals(500.0, registry.profile("minecraft:end_stone", Set.of()).mass(), EPSILON);
        assertEquals(1.0, registry.profile("minecraft:end_stone", Set.of()).buoyancyScale(), EPSILON);
        assertEquals(700.0, registry.profile("minecraft:oak_planks", Set.of("minecraft:planks")).mass(), EPSILON);
        assertTrue(registry.tagIds().contains("minecraft:logs"));
    }

    /**
     * Minimal resource manager used only to prove the "missing file = defaults"
     * path. No Minecraft bootstrap is required.
     */
    private static final class EmptyResourceManager implements ResourceManager {
        @Override
        public Set<String> getNamespaces() {
            return Set.of();
        }

        @Override
        public List<Resource> getResourceStack(Identifier id) {
            return List.of();
        }

        @Override
        public Map<Identifier, Resource> listResources(String path, Selector selector) {
            return Map.of();
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String path, Selector selector) {
            return Map.of();
        }

        @Override
        public Stream<net.minecraft.server.packs.PackResources> listPacks() {
            return Stream.empty();
        }

        @Override
        public Optional<Resource> getResource(Identifier id) {
            return Optional.empty();
        }
    }
}
