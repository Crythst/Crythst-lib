package io.mikaple.crythst.integration.minecraft.mass;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoublePredicate;
import java.util.stream.Collectors;

/**
 * Thread-safe registry of block physics profiles loaded from
 * {@code data/crythst/block_physics.json}.
 *
 * <p>Lookup precedence is:</p>
 * <ol>
 *   <li>an exact block id entry ({@code minecraft:stone});</li>
 *   <li>a block tag entry, in the order the tags appear in the data file;</li>
 *   <li>the data pack's {@code defaults} object, when present;</li>
 *   <li>the fallback profile supplied by {@link BlockPhysicsMapper} from
 *       {@code CrythstConfig}.</li>
 * </ol>
 *
 * <p>Lookups are lock-free after a reload: writers build a complete immutable
 * {@link Snapshot} and publish it through a volatile field. A small per-block
 * cache keeps tag extraction off the physics hot path; the cache is cleared on
 * every reload.</p>
 */
public final class BlockPhysicsRegistry {
    /** Data-pack path, relative to the {@code data} directory. */
    public static final String DATA_PATH = Crythst.MOD_ID + "/block_physics.json";
    /** Server-data resource id for {@link #DATA_PATH}. */
    public static final Identifier DATA_ID =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "block_physics.json");

    private static final BlockPhysicsRegistry SHARED =
            new BlockPhysicsRegistry(BlockPhysicsProfile.DEFAULT);

    private final Map<Block, CacheEntry> blockCache = new ConcurrentHashMap<>();

    private volatile BlockPhysicsProfile fallback;
    private volatile Snapshot snapshot;

    /** Only mutated while holding {@code this}. */
    private Parsed parsed = Parsed.EMPTY;
    private long generation;

    public BlockPhysicsRegistry(BlockPhysicsProfile fallback) {
        this.fallback = Validate.notNull(fallback, "fallback");
        this.snapshot = build(Parsed.EMPTY, fallback, 0L);
    }

    /**
     * Process-wide registry used by {@link BlockPhysicsMapper#defaults} and the
     * server reload listener.
     */
    public static BlockPhysicsRegistry shared() {
        return SHARED;
    }

    /**
     * Resolution result for a block. {@code explicit} is {@code true} when an
     * exact block entry or tag entry matched, as opposed to the defaults.
     */
    public record Resolution(BlockPhysicsProfile profile, boolean explicit) {
        public Resolution {
            Validate.notNull(profile, "profile");
        }
    }

    /** Effective defaults from the data pack, or empty when no data defaults exist. */
    public Optional<BlockPhysicsProfile> dataDefaults() {
        return Optional.ofNullable(snapshot.dataDefaults());
    }

    /** Defaults used when neither a block nor a tag entry matches. */
    public BlockPhysicsProfile defaults() {
        return snapshot.effectiveDefaults();
    }

    /** Fallback derived from the mod configuration. */
    public BlockPhysicsProfile fallback() {
        return fallback;
    }

    /**
     * Replaces the config fallback and re-resolves all stored overrides. Callers
     * normally let {@link BlockPhysicsMapper#defaults} do this during startup.
     */
    public synchronized void setFallbackDefaults(BlockPhysicsProfile newFallback) {
        Validate.notNull(newFallback, "newFallback");
        fallback = newFallback;
        generation++;
        rebuildLocked();
    }

    /**
     * Resolves a block id and its block tags. Tag strings may be passed with or
     * without a leading {@code #}.
     */
    public Resolution resolve(String blockId, Collection<String> blockTags) {
        final Snapshot current = snapshot;
        final String id = normalizeId(blockId);
        final BlockPhysicsProfile exact = current.blocks().get(id);
        if (exact != null) {
            return new Resolution(exact, true);
        }
        if (blockTags != null && !blockTags.isEmpty() && !current.tags().isEmpty()) {
            // Iterate configured tags, not the caller's collection, so the first
            // matching tag in data-file order wins deterministically.
            for (Map.Entry<String, BlockPhysicsProfile> entry : current.tags().entrySet()) {
                if (containsTag(blockTags, entry.getKey())) {
                    return new Resolution(entry.getValue(), true);
                }
            }
        }
        return new Resolution(current.effectiveDefaults(), false);
    }

    /**
     * Resolves a live block state. Tags are read from the block's built-in
     * holder and cached per {@link Block} until the next reload.
     */
    public Resolution resolve(BlockState state) {
        Validate.notNull(state, "state");
        final Snapshot current = snapshot;
        final Block block = state.getBlock();
        final CacheEntry cached = blockCache.get(block);
        if (cached != null && cached.generation() == current.generation()) {
            return cached.resolution();
        }

        final String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        final Set<String> tags = block.builtInRegistryHolder().tags()
                .map(tag -> tag.location().toString())
                .collect(Collectors.toUnmodifiableSet());
        final Resolution resolution = resolve(id, tags);
        blockCache.put(block, new CacheEntry(current.generation(), resolution));
        return resolution;
    }

    /** Convenience accessor used by buoyancy/interaction code. */
    public BlockPhysicsProfile profile(BlockState state) {
        return resolve(state).profile();
    }

    /** Convenience accessor for pure/id-based callers. */
    public BlockPhysicsProfile profile(String blockId, Collection<String> blockTags) {
        return resolve(blockId, blockTags).profile();
    }

    /**
     * Parses and installs a JSON document. On failure the previous snapshot is
     * kept untouched.
     *
     * @throws IllegalArgumentException when the document is structurally
     *                                  invalid or is not a JSON object
     */
    public void applyJson(String json) {
        Validate.notNull(json, "json");
        try {
            applyElement(JsonParser.parseString(json));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid block physics JSON", e);
        }
    }

    /**
     * Reads {@code data/crythst/block_physics.json} from a server data resource
     * manager. A missing file restores the config defaults.
     *
     * @return {@code true} when a file was present and applied
     */
    public boolean load(ResourceManager manager) {
        Validate.notNull(manager, "manager");
        final Optional<Resource> resource = manager.getResource(DATA_ID);
        if (resource.isEmpty()) {
            reset();
            Crythst.LOGGER.info("No {} found; using config block-mass defaults", DATA_PATH);
            return false;
        }
        try (Reader reader = resource.get().openAsReader()) {
            applyElement(JsonParser.parseReader(reader));
            final Snapshot current = snapshot;
            Crythst.LOGGER.info("Loaded {} ({} block entries, {} tag entries{})",
                    DATA_PATH, current.blocks().size(), current.tags().size(),
                    current.dataDefaults() == null ? "" : ", with data defaults");
            return true;
        } catch (IOException | RuntimeException e) {
            Crythst.LOGGER.warn("Could not parse {}; keeping the previous block-mass registry",
                    DATA_PATH, e);
            return false;
        }
    }

    /** Restores the config fallback and discards all data-pack entries. */
    public synchronized void reset() {
        parsed = Parsed.EMPTY;
        generation++;
        rebuildLocked();
    }

    /** Clears the per-block tag cache. Called automatically on reload. */
    public void invalidateCache() {
        blockCache.clear();
    }

    /** Number of exact block entries in the current snapshot. */
    public int blockCount() {
        return snapshot.blocks().size();
    }

    /** Number of tag entries in the current snapshot. */
    public int tagCount() {
        return snapshot.tags().size();
    }

    /** Configured tag ids, in precedence order. */
    public Set<String> tagIds() {
        return snapshot.tags().keySet();
    }

    private void applyElement(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("Block physics data must be a JSON object");
        }
        final Parsed next = parse(root.getAsJsonObject());
        synchronized (this) {
            parsed = next;
            generation++;
            rebuildLocked();
        }
    }

    private void rebuildLocked() {
        snapshot = build(parsed, fallback, generation);
        blockCache.clear();
    }

    private static Snapshot build(Parsed parsed, BlockPhysicsProfile fallback, long generation) {
        final BlockPhysicsProfile dataDefaults = parsed.hasDataDefaults()
                ? parsed.dataDefaults().resolve(fallback)
                : null;
        final BlockPhysicsProfile effective = dataDefaults != null ? dataDefaults : fallback;

        final Map<String, BlockPhysicsProfile> blocks = new LinkedHashMap<>();
        for (Map.Entry<String, Override> entry : parsed.blocks().entrySet()) {
            blocks.put(entry.getKey(), entry.getValue().resolve(effective));
        }
        final Map<String, BlockPhysicsProfile> tags = new LinkedHashMap<>();
        for (Map.Entry<String, Override> entry : parsed.tags().entrySet()) {
            tags.put(entry.getKey(), entry.getValue().resolve(effective));
        }

        return new Snapshot(immutable(blocks), immutable(tags), dataDefaults, effective, generation);
    }

    private static Parsed parse(JsonObject root) {
        Override dataDefaults = Override.EMPTY;
        boolean hasDataDefaults = false;
        if (root.has("defaults")) {
            final JsonElement defaults = root.get("defaults");
            if (!defaults.isJsonObject()) {
                throw new IllegalArgumentException("\"defaults\" must be a JSON object");
            }
            dataDefaults = parseOverride(defaults, "defaults");
            hasDataDefaults = true;
        }

        final Map<String, Override> blocks = new LinkedHashMap<>();
        final Map<String, Override> tags = new LinkedHashMap<>();

        if (root.has("blocks")) {
            final JsonElement element = root.get("blocks");
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("\"blocks\" must be a JSON object");
            }
            parseEntries(element.getAsJsonObject(), "blocks", false, blocks, tags);
        }
        if (root.has("tags")) {
            final JsonElement element = root.get("tags");
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("\"tags\" must be a JSON object");
            }
            parseEntries(element.getAsJsonObject(), "tags", true, blocks, tags);
        }

        return new Parsed(immutable(blocks), immutable(tags), dataDefaults, hasDataDefaults);
    }

    private static void parseEntries(JsonObject entries, String section, boolean forceTags,
                                     Map<String, Override> blocks,
                                     Map<String, Override> tags) {
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            final JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) {
                Crythst.LOGGER.warn("Ignoring {}.{}: expected a JSON object", section, key);
                continue;
            }
            final Override override = parseOverride(value, section + "." + key);
            if (forceTags || key.charAt(0) == '#') {
                // "tags" keys are always tags; inside "blocks" a leading '#'
                // marks a tag. Both forms accept an optional '#'.
                tags.put(key.startsWith("#") ? key.substring(1) : key, override);
            } else {
                blocks.put(key, override);
            }
        }
    }

    private static Override parseOverride(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) {
            return Override.EMPTY;
        }
        if (!element.isJsonObject()) {
            Crythst.LOGGER.warn("Ignoring {}: expected a JSON object", path);
            return Override.EMPTY;
        }
        final JsonObject object = element.getAsJsonObject();
        return new Override(
                readDouble(object, "mass", path, value -> value > 0.0, "a positive number"),
                readDouble(object, "friction", path, value -> value >= 0.0, "a non-negative number"),
                readDouble(object, "restitution", path,
                        value -> value >= 0.0 && value <= 1.0, "a number in [0, 1]"),
                readDouble(object, "rollingFriction", path,
                        value -> value >= 0.0, "a non-negative number"),
                readDouble(object, "spinningFriction", path,
                        value -> value >= 0.0, "a non-negative number"),
                readDouble(object, "buoyancyScale", path,
                        value -> value >= 0.0, "a non-negative number"));
    }

    private static Double readDouble(JsonObject object, String key, String path,
                                     DoublePredicate validator, String requirement) {
        final JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            Crythst.LOGGER.warn("Ignoring {} at {}: expected {}", key, path, requirement);
            return null;
        }
        final double value = element.getAsDouble();
        if (!Double.isFinite(value) || !validator.test(value)) {
            Crythst.LOGGER.warn("Ignoring invalid {}={} at {}: expected {}",
                    key, element, path, requirement);
            return null;
        }
        return value;
    }

    private static boolean containsTag(Collection<String> tags, String normalizedTag) {
        for (String tag : tags) {
            if (normalizeTag(tag).equals(normalizedTag)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String key) {
        final String trimmed = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("#") ? "#" + trimmed.substring(1) : trimmed;
    }

    private static String normalizeId(String id) {
        Validate.notNull(id, "blockId");
        final String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }

    private static String normalizeTag(String tag) {
        Validate.notNull(tag, "tag");
        final String trimmed = tag.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }

    private static <V> Map<String, V> immutable(Map<String, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private record CacheEntry(long generation, Resolution resolution) {
    }

    private record Snapshot(
            Map<String, BlockPhysicsProfile> blocks,
            Map<String, BlockPhysicsProfile> tags,
            BlockPhysicsProfile dataDefaults,
            BlockPhysicsProfile effectiveDefaults,
            long generation
    ) {
    }

    /**
     * Partial values from one JSON entry. {@code null} means "inherit from
     * defaults"; this is what makes both block entries and tags sparse.
     */
    private record Override(
            Double mass,
            Double friction,
            Double restitution,
            Double rollingFriction,
            Double spinningFriction,
            Double buoyancyScale
    ) {
        private static final Override EMPTY = new Override(null, null, null, null, null, null);

        private BlockPhysicsProfile resolve(BlockPhysicsProfile defaults) {
            return new BlockPhysicsProfile(
                    mass != null ? mass : defaults.mass(),
                    friction != null ? friction : defaults.friction(),
                    restitution != null ? restitution : defaults.restitution(),
                    rollingFriction != null ? rollingFriction : defaults.rollingFriction(),
                    spinningFriction != null ? spinningFriction : defaults.spinningFriction(),
                    buoyancyScale != null ? buoyancyScale : defaults.buoyancyScale());
        }
    }

    private record Parsed(
            Map<String, Override> blocks,
            Map<String, Override> tags,
            Override dataDefaults,
            boolean hasDataDefaults
    ) {
        private static final Parsed EMPTY =
                new Parsed(Map.of(), Map.of(), Override.EMPTY, false);
    }
}
