package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, serializable snapshot of a {@link VoxelVolume}.
 *
 * <p>The snapshot keeps the exact solid mask plus the per-cell mass and
 * material, so rebuilding a structure preserves its centre of mass and inertia.
 * Block states are optional: they are copied from a {@link BlockVoxelVolume} or
 * from {@link StructureBlockStates}, and absent states fall back to
 * {@link Blocks#STONE} for solid cells. This mirrors how
 * {@code EmptyPhysicsLevel} already treats non-block-backed volumes.</p>
 *
 * <p>NBT layout (all coordinates are structure-local voxels):</p>
 * <pre>
 * Bounds           int[6]  minX,minY,minZ,maxX,maxY,maxZ
 * Solid            byte[]  one byte per cell (0/1)
 * MassPalette      list of doubles
 * MassIndex        int[]   one palette index per cell
 * MaterialPalette  list of compounds (friction, restitution, ...)
 * MaterialIndex    int[]   one palette index per cell
 * StatePalette     optional list of block-state compounds
 * StateIndex       optional int[]; -1 means "no explicit state"
 * </pre>
 */
public final class SavedStructureVolume implements StatefulVoxelVolume {
    private static final double DEFAULT_MASS = 1000.0;

    private final IntBounds3 bounds;
    private final boolean[] solid;
    private final double[] mass;
    private final PhysicsMaterial[] materials;
    private final double fallbackMass;
    private final PhysicsMaterial fallbackMaterial;
    /** {@code null} when the source volume had no block-state information. */
    private final BlockState[] states;

    private SavedStructureVolume(IntBounds3 bounds, boolean[] solid, double[] mass,
                                 PhysicsMaterial[] materials, double fallbackMass,
                                 PhysicsMaterial fallbackMaterial, BlockState[] states) {
        this.bounds = Validate.notNull(bounds, "bounds");
        this.solid = Validate.notNull(solid, "solid");
        this.mass = Validate.notNull(mass, "mass");
        this.materials = Validate.notNull(materials, "materials");
        this.fallbackMass = fallbackMass > 0.0 ? fallbackMass : DEFAULT_MASS;
        this.fallbackMaterial = fallbackMaterial == null
                ? PhysicsMaterial.DEFAULT : fallbackMaterial;
        final int cells = Math.toIntExact(bounds.volume());
        if (solid.length != cells || mass.length != cells || materials.length != cells) {
            throw new IllegalArgumentException("Cell arrays must match the volume: expected " + cells);
        }
        if (states != null && states.length != cells) {
            throw new IllegalArgumentException("State array must match the volume: expected " + cells);
        }
        this.states = states;
    }

    /**
     * Snapshots a volume.
     *
     * <p>{@link BlockVoxelVolume} contributes real block states. Other volumes
     * (including {@link MutableVoxelVolume}) have no state storage, so their
     * solid cells report {@link Blocks#STONE} when queried later. Callers that
     * have a live structure should prefer {@link #fromStructure(PhysicsStructure)}
     * so states kept in {@link StructureBlockStates} are included.</p>
     */
    public static SavedStructureVolume fromVolume(VoxelVolume volume) {
        return snapshot(volume, null);
    }

    /**
     * Snapshots a live structure, including side-table block states for mutable
     * volumes.
     */
    public static SavedStructureVolume fromStructure(PhysicsStructure structure) {
        Objects.requireNonNull(structure, "structure");
        return snapshot(structure.volume(), structure);
    }

    private static SavedStructureVolume snapshot(VoxelVolume volume, PhysicsStructure structure) {
        Validate.notNull(volume, "volume");
        final IntBounds3 bounds = volume.bounds();
        final int cells = Math.toIntExact(bounds.volume());
        final boolean[] solid = new boolean[cells];
        final double[] mass = new double[cells];
        final PhysicsMaterial[] materials = new PhysicsMaterial[cells];
        final BlockState[] states = volume instanceof BlockVoxelVolume || structure != null
                ? new BlockState[cells] : null;

        double fallbackMass = DEFAULT_MASS;
        PhysicsMaterial fallbackMaterial = PhysicsMaterial.DEFAULT;
        boolean firstSolid = true;
        boolean anyState = false;

        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final int index = index(bounds, x, y, z);
                    if (!volume.isSolid(x, y, z)) {
                        materials[index] = PhysicsMaterial.DEFAULT;
                        continue;
                    }
                    solid[index] = true;
                    final double cellMass = volume.blockMass(x, y, z);
                    mass[index] = cellMass > 0.0 ? cellMass : DEFAULT_MASS;
                    final PhysicsMaterial material = volume.blockMaterial(x, y, z);
                    materials[index] = material == null ? PhysicsMaterial.DEFAULT : material;
                    if (firstSolid) {
                        fallbackMass = mass[index];
                        fallbackMaterial = materials[index];
                        firstSolid = false;
                    }

                    if (states != null) {
                        BlockState state = null;
                        if (volume instanceof BlockVoxelVolume blockVolume) {
                            state = blockVolume.blockState(x, y, z);
                        } else if (volume instanceof StatefulVoxelVolume stateful) {
                            state = stateful.blockState(x, y, z);
                        } else if (structure != null) {
                            state = StructureBlockStates.get(structure, x, y, z);
                        }
                        if (state != null && !state.isAir()) {
                            states[index] = state;
                            anyState = true;
                        }
                    }
                }
            }
        }

        return new SavedStructureVolume(bounds, solid, mass, materials,
                fallbackMass, fallbackMaterial, anyState ? states : null);
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return solid[index(bounds, x, y, z)];
    }

    @Override
    public double blockMass(int x, int y, int z) {
        final int index = index(bounds, x, y, z);
        if (!solid[index]) {
            return fallbackMass;
        }
        final double value = mass[index];
        return value > 0.0 ? value : fallbackMass;
    }

    @Override
    public PhysicsMaterial blockMaterial(int x, int y, int z) {
        final int index = index(bounds, x, y, z);
        final PhysicsMaterial material = materials[index];
        return material == null ? fallbackMaterial : material;
    }

    /**
     * Block state for a voxel, or {@code null} outside the bounds.
     * Non-solid cells report air; solid cells without an explicit state report
     * stone.
     */
    public BlockState blockState(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return null;
        }
        final int index = index(bounds, x, y, z);
        if (!solid[index]) {
            return Blocks.AIR.defaultBlockState();
        }
        if (states != null && states[index] != null) {
            return states[index];
        }
        return Blocks.STONE.defaultBlockState();
    }

    /** Whether this snapshot carries explicit block states. */
    public boolean hasBlockStates() {
        return states != null;
    }

    /**
     * Builds an editable dense volume with the same solids, mass and material.
     * Block states are not part of {@link MutableVoxelVolume}; restore them in
     * {@link StructureBlockStates} when reconstructing a live structure.
     */
    public MutableVoxelVolume toMutableVolume() {
        final MutableVoxelVolume volume = new MutableVoxelVolume(bounds);
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!isSolid(x, y, z)) {
                        continue;
                    }
                    volume.setBlock(x, y, z, true, blockMass(x, y, z), blockMaterial(x, y, z));
                }
            }
        }
        return volume;
    }

    public StructureDef toStructureDef(Transform3d pose) {
        return toStructureDef(pose, StructureParameters.defaults());
    }

    /**
     * Rebuilds a structure definition. The returned definition owns a fresh
     * mutable volume so the restored structure can still be edited by the
     * interaction layer.
     */
    public StructureDef toStructureDef(Transform3d pose, StructureParameters parameters) {
        Validate.notNull(pose, "pose");
        Validate.notNull(parameters, "parameters");
        return parameters.applyTo(StructureDef.builder(new RestoredStructureVolume(this)))
                .pose(pose)
                .build();
    }

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("Bounds", new int[]{
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()
        });

        final byte[] solidBytes = new byte[solid.length];
        for (int i = 0; i < solid.length; i++) {
            solidBytes[i] = (byte) (solid[i] ? 1 : 0);
        }
        tag.putByteArray("Solid", solidBytes);

        writeDoublePalette(tag, "MassPalette", "MassIndex", mass, fallbackMass);
        writeMaterialPalette(tag, materials, fallbackMaterial);
        if (states != null) {
            writeStatePalette(tag);
        }
        return tag;
    }

    /**
     * Reads a snapshot from NBT.
     *
     * @param blocks block registry lookup used to resolve explicit block states;
     *               may be {@code null} only when the tag contains no states
     */
    public static SavedStructureVolume fromTag(CompoundTag tag, HolderGetter<Block> blocks) {
        Validate.notNull(tag, "tag");
        final int[] packedBounds = tag.getIntArray("Bounds").orElse(null);
        if (packedBounds == null || packedBounds.length != 6) {
            throw new IllegalArgumentException("Saved volume is missing a valid Bounds array");
        }
        final IntBounds3 bounds = new IntBounds3(packedBounds[0], packedBounds[1], packedBounds[2],
                packedBounds[3], packedBounds[4], packedBounds[5]);
        final int cells = Math.toIntExact(bounds.volume());

        final byte[] solidBytes = tag.getByteArray("Solid").orElse(new byte[0]);
        if (solidBytes.length != cells) {
            throw new IllegalArgumentException("Saved volume Solid array has length "
                    + solidBytes.length + ", expected " + cells);
        }
        final boolean[] solid = new boolean[cells];
        for (int i = 0; i < cells; i++) {
            solid[i] = solidBytes[i] != 0;
        }

        final double fallbackMass = Math.max(0.0, tag.getDoubleOr("FallbackMass", DEFAULT_MASS));
        final double[] mass = readDoublePalette(tag, cells, fallbackMass);
        final PhysicsMaterial fallbackMaterial = readMaterial(tag.getCompoundOrEmpty("FallbackMaterial"),
                PhysicsMaterial.DEFAULT);
        final PhysicsMaterial[] materials = readMaterialPalette(tag, cells, fallbackMaterial);

        BlockState[] states = null;
        if (tag.contains("StateIndex")) {
            if (blocks == null) {
                throw new IllegalArgumentException(
                        "Saved volume contains block states but no block registry was supplied");
            }
            states = readStatePalette(tag, cells, blocks);
        }

        return new SavedStructureVolume(bounds, solid, mass, materials,
                fallbackMass, fallbackMaterial, states);
    }

    private static int index(IntBounds3 bounds, int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Voxel " + x + "," + y + "," + z + " outside " + bounds);
        }
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }

    private static void writeDoublePalette(CompoundTag tag, String paletteKey, String indexKey,
                                           double[] values, double fallback) {
        final Map<Double, Integer> palette = new LinkedHashMap<>();
        final int[] indices = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            final double value = values[i] > 0.0 ? values[i] : fallback;
            indices[i] = palette.computeIfAbsent(value, ignored -> palette.size());
        }
        final ListTag paletteTag = new ListTag();
        for (double value : palette.keySet()) {
            paletteTag.add(DoubleTag.valueOf(value));
        }
        tag.put(paletteKey, paletteTag);
        tag.putIntArray(indexKey, indices);
        tag.putDouble("FallbackMass", fallback);
    }

    private static double[] readDoublePalette(CompoundTag tag, int cells, double fallback) {
        final ListTag palette = tag.getListOrEmpty("MassPalette");
        final int[] indices = tag.getIntArray("MassIndex").orElse(null);
        final double[] values = new double[cells];
        java.util.Arrays.fill(values, fallback);
        if (indices == null) {
            return values;
        }
        if (indices.length != cells) {
            throw new IllegalArgumentException("MassIndex has length " + indices.length
                    + ", expected " + cells);
        }
        for (int i = 0; i < cells; i++) {
            final int paletteIndex = indices[i];
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                continue;
            }
            values[i] = palette.getDoubleOr(paletteIndex, fallback);
        }
        return values;
    }

    private static void writeMaterialPalette(CompoundTag tag, PhysicsMaterial[] materials,
                                             PhysicsMaterial fallback) {
        final Map<PhysicsMaterial, Integer> palette = new LinkedHashMap<>();
        final int[] indices = new int[materials.length];
        for (int i = 0; i < materials.length; i++) {
            final PhysicsMaterial value = materials[i] == null ? fallback : materials[i];
            indices[i] = palette.computeIfAbsent(value, ignored -> palette.size());
        }
        final ListTag paletteTag = new ListTag();
        for (PhysicsMaterial value : palette.keySet()) {
            final CompoundTag entry = new CompoundTag();
            entry.putDouble("Friction", value.friction());
            entry.putDouble("Restitution", value.restitution());
            entry.putDouble("RollingFriction", value.rollingFriction());
            entry.putDouble("SpinningFriction", value.spinningFriction());
            entry.putDouble("Density", value.density());
            paletteTag.add(entry);
        }
        tag.put("MaterialPalette", paletteTag);
        tag.putIntArray("MaterialIndex", indices);
        tag.put("FallbackMaterial", writeMaterial(fallback));
    }

    private static PhysicsMaterial[] readMaterialPalette(CompoundTag tag, int cells,
                                                         PhysicsMaterial fallback) {
        final ListTag palette = tag.getListOrEmpty("MaterialPalette");
        final int[] indices = tag.getIntArray("MaterialIndex").orElse(null);
        final PhysicsMaterial[] values = new PhysicsMaterial[cells];
        java.util.Arrays.fill(values, fallback);
        if (indices == null) {
            return values;
        }
        if (indices.length != cells) {
            throw new IllegalArgumentException("MaterialIndex has length " + indices.length
                    + ", expected " + cells);
        }
        for (int i = 0; i < cells; i++) {
            final int paletteIndex = indices[i];
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                continue;
            }
            values[i] = readMaterial(palette.getCompoundOrEmpty(paletteIndex), fallback);
        }
        return values;
    }

    private static CompoundTag writeMaterial(PhysicsMaterial material) {
        final CompoundTag tag = new CompoundTag();
        tag.putDouble("Friction", material.friction());
        tag.putDouble("Restitution", material.restitution());
        tag.putDouble("RollingFriction", material.rollingFriction());
        tag.putDouble("SpinningFriction", material.spinningFriction());
        tag.putDouble("Density", material.density());
        return tag;
    }

    private static PhysicsMaterial readMaterial(CompoundTag tag, PhysicsMaterial fallback) {
        if (tag == null || tag.isEmpty()) {
            return fallback;
        }
        try {
            return new PhysicsMaterial(
                    tag.getDoubleOr("Friction", fallback.friction()),
                    tag.getDoubleOr("Restitution", fallback.restitution()),
                    tag.getDoubleOr("RollingFriction", fallback.rollingFriction()),
                    tag.getDoubleOr("SpinningFriction", fallback.spinningFriction()),
                    positiveOr(tag.getDoubleOr("Density", fallback.density()), fallback.density()));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private void writeStatePalette(CompoundTag tag) {
        final Map<BlockState, Integer> palette = new LinkedHashMap<>();
        final int[] indices = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            final BlockState state = states[i];
            if (state == null) {
                indices[i] = -1;
                continue;
            }
            indices[i] = palette.computeIfAbsent(state, ignored -> palette.size());
        }
        final ListTag paletteTag = new ListTag();
        for (BlockState state : palette.keySet()) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        tag.put("StatePalette", paletteTag);
        tag.putIntArray("StateIndex", indices);
    }

    private static BlockState[] readStatePalette(CompoundTag tag, int cells,
                                                 HolderGetter<Block> blocks) {
        final ListTag palette = tag.getListOrEmpty("StatePalette");
        final int[] indices = tag.getIntArray("StateIndex").orElse(null);
        if (indices == null || indices.length != cells) {
            return null;
        }
        final BlockState[] resolved = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            resolved[i] = NbtUtils.readBlockState(blocks, palette.getCompoundOrEmpty(i));
        }
        final BlockState[] values = new BlockState[cells];
        for (int i = 0; i < cells; i++) {
            final int paletteIndex = indices[i];
            if (paletteIndex >= 0 && paletteIndex < resolved.length) {
                values[i] = resolved[paletteIndex];
            }
        }
        return values;
    }

    private static double positiveOr(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }
}
