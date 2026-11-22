package io.mikaple.crythst.api.schematic;

import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.storage.SavedStructureVolume;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable schematic decoded from NBT, ready to become a Crythst structure
 * without placing any block in the host world.
 *
 * <p>Two input formats are supported:</p>
 * <ul>
 *   <li><strong>Vanilla structure NBT</strong> ({@code size}, {@code palette},
 *       {@code blocks}, optional block-entity {@code nbt}). This is the format
 *       written by structure blocks and most template packs.</li>
 *   <li><strong>Crythst volume NBT</strong> ({@code Bounds}, {@code Solid},
 *       optional mass/material/state palettes), as produced by
 *       {@link SavedStructureVolume#toTag()}.</li>
 * </ul>
 *
 * <p>The decoded volume keeps block states for non-solid logic blocks
 * (redstone, torches, buttons, fluids) as well as for collision blocks, and
 * keeps block-entity NBT in a separate map so it can be reattached to the
 * auxiliary ticking level. Nothing is written to the host level.</p>
 *
 * <p><strong>Threading:</strong> loading needs only the block registry and is
 * safe on the server thread. Registry access must be the caller's; a client
 * level's registry access works for client-side previews, but instantiation is
 * server-only through {@link LevelPhysics}.</p>
 */
public final class Schematic {
    /** Input format detected from the NBT root. */
    public enum Format {
        /** Vanilla structure-block/template NBT. */
        VANILLA_STRUCTURE,
        /** Crythst {@link SavedStructureVolume} NBT. */
        CRYTHST_VOLUME
    }

    private final Format format;
    private final int dataVersion;
    private final IntBounds3 bounds;
    private final SchematicVolume volume;
    private final List<BlockState> palette;
    private final Map<BlockPos, CompoundTag> blockEntities;

    private Schematic(Format format, int dataVersion, IntBounds3 bounds,
                      SchematicVolume volume, List<BlockState> palette,
                      Map<BlockPos, CompoundTag> blockEntities) {
        this.format = Objects.requireNonNull(format, "format");
        this.dataVersion = dataVersion;
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.volume = Objects.requireNonNull(volume, "volume");
        this.palette = List.copyOf(palette);
        this.blockEntities = Collections.unmodifiableMap(new LinkedHashMap<>(blockEntities));
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Detects the NBT format and decodes it with {@link SchematicLimits#DEFAULT}.
     *
     * @param tag    decompressed NBT root
     * @param blocks block registry lookup
     * @return the decoded schematic
     * @throws SchematicException when the format is unsupported, malformed or
     *                            exceeds the default limits
     */
    public static Schematic load(CompoundTag tag, HolderGetter<Block> blocks) {
        return load(tag, blocks, SchematicLimits.DEFAULT);
    }

    /**
     * Detects the NBT format and decodes it.
     *
     * @param tag    decompressed NBT root
     * @param blocks block registry lookup
     * @param limits resource limits
     * @return the decoded schematic
     * @throws SchematicException when the format is unsupported, malformed or
     *                            exceeds the limits
     */
    public static Schematic load(CompoundTag tag, HolderGetter<Block> blocks,
                                 SchematicLimits limits) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(limits, "limits");
        if (tag.contains("BlockData") || tag.contains("BlockDataVersion")
                || tag.contains("Blocks")
                || (tag.contains("Width") && tag.contains("Height")
                && tag.contains("Length"))) {
            throw new SchematicException("Sponge schematic NBT is not supported; use vanilla "
                    + "structure NBT or a Crythst volume definition");
        }
        if (tag.contains("Bounds") && tag.contains("Solid")) {
            return loadCrythstVolume(tag, blocks, limits);
        }
        if (tag.contains("size") || tag.contains("palette") || tag.contains("palettes")) {
            return loadVanillaStructure(tag, blocks, limits);
        }
        throw new SchematicException("Unrecognised schematic NBT: expected vanilla structure "
                + "size/palette/blocks tags or Crythst Bounds/Solid tags");
    }

    /**
     * Convenience overload using a registry-access provider.
     */
    public static Schematic load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, blockLookup(registries), SchematicLimits.DEFAULT);
    }

    /**
     * Convenience overload using the block registry of a level.
     */
    public static Schematic load(CompoundTag tag, Level level) {
        return load(tag, blockLookup(level.registryAccess()), SchematicLimits.DEFAULT);
    }

    /**
     * Convenience overload using the block registry of a level and explicit
     * limits.
     */
    public static Schematic load(CompoundTag tag, Level level, SchematicLimits limits) {
        return load(tag, blockLookup(level.registryAccess()), limits);
    }

    /**
     * Convenience overload using a registry-access provider and explicit limits.
     */
    public static Schematic load(CompoundTag tag, HolderLookup.Provider registries,
                                 SchematicLimits limits) {
        return load(tag, blockLookup(registries), limits);
    }

    /**
     * Reads and decodes a gzip-compressed {@code .nbt} file with the default
     * limits.
     *
     * @throws IOException when the file cannot be read
     * @throws SchematicException when decoding fails
     */
    public static Schematic loadCompressed(Path file, HolderGetter<Block> blocks)
            throws IOException {
        return loadCompressed(file, blocks, SchematicLimits.DEFAULT);
    }

    /**
     * Reads and decodes a gzip-compressed {@code .nbt} file.
     *
     * <p>The NBT reader is bounded by {@link SchematicLimits#maxNbtBytes()} so
     * a malicious or corrupt file cannot allocate unbounded heap before the
     * size checks run.</p>
     *
     * @throws IOException when the file cannot be read
     * @throws SchematicException when decoding fails
     */
    public static Schematic loadCompressed(Path file, HolderGetter<Block> blocks,
                                           SchematicLimits limits) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(limits, "limits");
        final CompoundTag tag = NbtIo.readCompressed(file,
                NbtAccounter.create(limits.maxNbtBytes()));
        return load(tag, blocks, limits);
    }

    /**
     * Convenience overload for a registry-access provider.
     */
    public static Schematic loadCompressed(Path file, HolderLookup.Provider registries)
            throws IOException {
        return loadCompressed(file, blockLookup(registries), SchematicLimits.DEFAULT);
    }

    /**
     * Convenience overload using the block registry of a level.
     */
    public static Schematic loadCompressed(Path file, Level level) throws IOException {
        return loadCompressed(file, blockLookup(level.registryAccess()), SchematicLimits.DEFAULT);
    }

    /**
     * Convenience overload using the block registry of a level and explicit
     * limits.
     */
    public static Schematic loadCompressed(Path file, Level level, SchematicLimits limits)
            throws IOException {
        return loadCompressed(file, blockLookup(level.registryAccess()), limits);
    }

    /**
     * Reads and decodes a gzip-compressed NBT stream with the default limits.
     * The stream is closed by this method.
     */
    public static Schematic loadCompressed(InputStream input, HolderGetter<Block> blocks)
            throws IOException {
        return loadCompressed(input, blocks, SchematicLimits.DEFAULT);
    }

    /**
     * Reads and decodes a gzip-compressed NBT stream. The stream is closed.
     */
    public static Schematic loadCompressed(InputStream input, HolderGetter<Block> blocks,
                                           SchematicLimits limits) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(limits, "limits");
        final CompoundTag tag = NbtIo.readCompressed(input,
                NbtAccounter.create(limits.maxNbtBytes()));
        return load(tag, blocks, limits);
    }

    private static HolderGetter<Block> blockLookup(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        return registries.lookupOrThrow(Registries.BLOCK);
    }

    private static Schematic loadVanillaStructure(CompoundTag tag, HolderGetter<Block> blocks,
                                                  SchematicLimits limits) {
        final ListTag sizeTag = tag.getListOrEmpty("size");
        if (sizeTag.size() < 3) {
            throw new SchematicException("Vanilla structure NBT is missing a size[3] array");
        }
        final int sizeX = sizeTag.getIntOr(0, 0);
        final int sizeY = sizeTag.getIntOr(1, 0);
        final int sizeZ = sizeTag.getIntOr(2, 0);
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new SchematicException("Vanilla structure size must be positive: "
                    + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, sizeX, sizeY, sizeZ);
        limits.validateBounds(bounds);

        final ListTag paletteTag = selectPalette(tag);
        final BlockState[] declaredPalette = new BlockState[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            try {
                declaredPalette[i] = NbtUtils.readBlockState(
                        blocks, paletteTag.getCompoundOrEmpty(i));
            } catch (RuntimeException failure) {
                throw new SchematicException("Invalid block state at palette index " + i,
                        failure);
            }
        }

        final SchematicVolume volume = new SchematicVolume(bounds);
        final Set<BlockState> palette = new LinkedHashSet<>();
        final Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        int nonAir = 0;

        final ListTag blockList = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blockList.size(); i++) {
            final CompoundTag blockTag = blockList.getCompoundOrEmpty(i);
            final ListTag posTag = blockTag.getListOrEmpty("pos");
            if (posTag.size() < 3) {
                throw new SchematicException("Block entry " + i + " is missing pos[3]");
            }
            final int x = posTag.getIntOr(0, 0);
            final int y = posTag.getIntOr(1, 0);
            final int z = posTag.getIntOr(2, 0);
            if (!bounds.contains(x, y, z)) {
                throw new SchematicException("Block entry " + i + " at " + x + "," + y + "," + z
                        + " is outside the " + sizeX + "x" + sizeY + "x" + sizeZ + " volume");
            }
            final int stateIndex = blockTag.getIntOr("state", 0);
            if (stateIndex < 0 || stateIndex >= declaredPalette.length) {
                throw new SchematicException("Block entry " + i + " has palette index "
                        + stateIndex + ", palette size is " + declaredPalette.length);
            }
            final BlockState state = declaredPalette[stateIndex];
            if (state == null || state.isAir()) {
                continue;
            }
            if (volume.blockState(x, y, z) != null) {
                throw new SchematicException("Duplicate block entry at " + x + "," + y + "," + z);
            }
            addBlock(volume, palette, state, x, y, z);
            nonAir++;
            limits.validateBlockCount(nonAir);
            final CompoundTag nbt = blockTag.getCompound("nbt").orElse(null);
            if (nbt != null && !nbt.isEmpty()) {
                blockEntities.put(new BlockPos(x - bounds.minX(), y - bounds.minY(),
                        z - bounds.minZ()), nbt.copy());
            }
        }

        return new Schematic(Format.VANILLA_STRUCTURE, tag.getIntOr("DataVersion", 0),
                bounds, volume, new ArrayList<>(palette), blockEntities);
    }

    private static Schematic loadCrythstVolume(CompoundTag tag, HolderGetter<Block> blocks,
                                               SchematicLimits limits) {
        final SavedStructureVolume saved = SavedStructureVolume.fromTag(tag, blocks);
        final IntBounds3 bounds = saved.bounds();
        limits.validateBounds(bounds);

        final SchematicVolume volume = new SchematicVolume(bounds);
        final Set<BlockState> palette = new LinkedHashSet<>();
        int nonAir = 0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final BlockState state = saved.blockState(x, y, z);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    addBlock(volume, palette, state, x, y, z,
                            saved.blockMass(x, y, z), saved.blockMaterial(x, y, z));
                    nonAir++;
                    limits.validateBlockCount(nonAir);
                }
            }
        }

        return new Schematic(Format.CRYTHST_VOLUME, tag.getIntOr("DataVersion", 0),
                bounds, volume, new ArrayList<>(palette), Map.of());
    }

    private static ListTag selectPalette(CompoundTag tag) {
        if (tag.contains("palettes")) {
            final ListTag palettes = tag.getListOrEmpty("palettes");
            if (palettes.isEmpty()) {
                throw new SchematicException("Vanilla structure NBT has an empty palettes list");
            }
            return palettes.getListOrEmpty(0);
        }
        final ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            throw new SchematicException("Vanilla structure NBT has no palette entries");
        }
        return palette;
    }

    private static void addBlock(SchematicVolume volume, Set<BlockState> palette,
                                 BlockState state, int x, int y, int z) {
        final BlockPhysicsMapper.BlockProperties properties =
                MinecraftPhysics.blockPhysics().properties(state);
        final double mass = properties.mass() > 0.0 && Double.isFinite(properties.mass())
                ? properties.mass()
                : Math.max(1.0, MinecraftPhysics.config().defaultBlockMass);
        addBlock(volume, palette, state, x, y, z, mass, properties.material());
    }

    private static void addBlock(SchematicVolume volume, Set<BlockState> palette,
                                 BlockState state, int x, int y, int z,
                                 double mass, PhysicsMaterial material) {
        // A full block and any non-empty partial shape (stairs, slabs, fences)
        // participate in collision. Logic blocks and fluids keep their state
        // but stay out of the collider.
        final boolean solid = state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                || !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        volume.setBlock(x, y, z, state, solid, mass, material);
        palette.add(state);
    }

    // ------------------------------------------------------------------
    // Query and instantiation
    // ------------------------------------------------------------------

    /** Detected input format. */
    public Format format() {
        return format;
    }

    /**
     * Minecraft {@code DataVersion} stored in the file, or {@code 0} when the
     * input did not contain one. DataFixerUpper migration is not applied.
     */
    public int dataVersion() {
        return dataVersion;
    }

    /** Integer bounds of the decoded volume. */
    public IntBounds3 bounds() {
        return bounds;
    }

    public int sizeX() {
        return bounds.sizeX();
    }

    public int sizeY() {
        return bounds.sizeY();
    }

    public int sizeZ() {
        return bounds.sizeZ();
    }

    /** Read-only voxel volume, including non-solid logic blocks. */
    public SchematicVolume volume() {
        return volume;
    }

    /**
     * Distinct block states encountered in the volume, in first-seen order.
     * Vanilla palette entries that are unused are not included.
     */
    public List<BlockState> palette() {
        return palette;
    }

    /** Number of non-air cells, including non-solid logic blocks. */
    public int nonAirBlockCount() {
        return volume.nonAirCount();
    }

    /** Number of cells with collision geometry. */
    public int solidBlockCount() {
        return volume.solidCount();
    }

    /**
     * Block-entity NBT keyed by structure-local voxel relative to
     * {@link #bounds()} minimum.
     *
     * <p>A defensive copy is returned so callers cannot mutate the decoded
     * schematic. Block tags are copied as well.</p>
     */
    public Map<BlockPos, CompoundTag> blockEntities() {
        final Map<BlockPos, CompoundTag> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, CompoundTag> entry : blockEntities.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Block state at a volume voxel.
     *
     * @return the state, or {@code null} outside the volume or for an empty cell
     */
    public @Nullable BlockState blockStateAt(int x, int y, int z) {
        return volume.blockState(x, y, z);
    }

    /**
     * Builds a definition with {@link SchematicOptions#DEFAULTS}.
     *
     * @throws SchematicException when the volume has no solid blocks
     */
    public StructureDef toStructureDef() {
        return toStructureDef(SchematicOptions.DEFAULTS);
    }

    /**
     * Builds a definition at the given pose with the remaining default options.
     */
    public StructureDef toStructureDef(Transform3d pose) {
        return toStructureDef(SchematicOptions.DEFAULTS.withPose(pose));
    }

    /**
     * Builds a definition with an explicit pose and block scale.
     */
    public StructureDef toStructureDef(Transform3d pose, double blockScale) {
        return toStructureDef(SchematicOptions.DEFAULTS
                .withPose(pose)
                .withBlockScale(blockScale));
    }

    /**
     * Builds a definition with full options.
     *
     * @throws SchematicException when the volume has no solid blocks
     */
    public StructureDef toStructureDef(SchematicOptions options) {
        Objects.requireNonNull(options, "options");
        if (volume.solidCount() == 0) {
            throw new SchematicException("Schematic contains no solid blocks with collision geometry");
        }
        return options.applyTo(StructureDef.builder(volume)).build();
    }

    /**
     * Creates a live structure in the given physics world without placing the
     * blocks in the host level.
     *
     * <p>Block-entity NBT is stored in
     * {@link StructureBlockEntityData} and, when an auxiliary ticking level is
     * available, reattached through {@link LevelPhysics#applyBlockEntities}.
     * Non-solid logic blocks are copied by the auxiliary level because the
     * volume implements {@code StatefulVoxelVolume}.</p>
     *
     * @param physics physics world for the target dimension
     * @return the created structure
     */
    public PhysicsStructure instantiate(LevelPhysics physics) {
        return instantiate(physics, SchematicOptions.DEFAULTS);
    }

    /**
     * Creates a live structure at a pose with default options.
     */
    public PhysicsStructure instantiate(LevelPhysics physics, Transform3d pose) {
        return instantiate(physics, SchematicOptions.DEFAULTS.withPose(pose));
    }

    /**
     * Creates a live structure with explicit options.
     *
     * @param physics physics world for the target dimension
     * @param options placement and body options
     * @return the created structure
     */
    public PhysicsStructure instantiate(LevelPhysics physics, SchematicOptions options) {
        Objects.requireNonNull(physics, "physics");
        Objects.requireNonNull(options, "options");
        final PhysicsStructure structure = physics.createStructure(toStructureDef(options));
        if (options.attachBlockEntities() && !blockEntities.isEmpty()) {
            StructureBlockEntityData.put(structure, blockEntities);
            physics.applyBlockEntities(structure, blockEntities);
        }
        return structure;
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    /**
     * Writes the schematic as vanilla structure NBT.
     *
     * <p>The output is normalised to a zero-based bounding box. It can be read
     * back with {@link #load(CompoundTag, HolderGetter)} and also imported by
     * vanilla structure blocks. Non-solid logic blocks and block-entity NBT are
     * preserved.</p>
     */
    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.put("size", intList(sizeX(), sizeY(), sizeZ()));

        final Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        final ListTag paletteTag = new ListTag();
        // Preserve the decoded palette order, then append any state that was
        // added after decoding (currently none, but keeps the writer robust).
        for (BlockState state : palette) {
            if (state != null && !state.isAir() && !paletteIndex.containsKey(state)) {
                paletteIndex.put(state, paletteTag.size());
                paletteTag.add(NbtUtils.writeBlockState(state));
            }
        }
        final ListTag blockList = new ListTag();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final BlockState state = volume.blockState(x, y, z);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    Integer index = paletteIndex.get(state);
                    if (index == null) {
                        index = paletteTag.size();
                        paletteTag.add(NbtUtils.writeBlockState(state));
                        paletteIndex.put(state, index);
                    }
                    final CompoundTag blockTag = new CompoundTag();
                    blockTag.put("pos", intList(x - bounds.minX(), y - bounds.minY(),
                            z - bounds.minZ()));
                    blockTag.putInt("state", index);
                    final CompoundTag nbt = blockEntities.get(new BlockPos(
                            x - bounds.minX(), y - bounds.minY(), z - bounds.minZ()));
                    if (nbt != null && !nbt.isEmpty()) {
                        blockTag.put("nbt", nbt.copy());
                    }
                    blockList.add(blockTag);
                }
            }
        }

        tag.put("palette", paletteTag);
        tag.put("blocks", blockList);
        tag.put("entities", new ListTag());
        NbtUtils.addCurrentDataVersion(tag);
        return tag;
    }

    private static ListTag intList(int... values) {
        final ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }
}
