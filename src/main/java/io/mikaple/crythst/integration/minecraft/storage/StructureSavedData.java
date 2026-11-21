package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-dimension saved data holding physics structure snapshots.
 *
 * <p>The data is auto discovered: every live structure in
 * {@link LevelPhysics#structures()} is captured when the server saves. Stable
 * UUIDs let a restored structure keep its identity across saves even though the
 * physics body id is not stable.</p>
 *
 * <p>Block entity NBT is captured for structures backed by a
 * {@link BlockVoxelVolume} using
 * {@link BlockEntity#saveWithFullMetadata(net.minecraft.core.HolderLookup.Provider)}.
 * Mutable volumes have no block entity source.</p>
 */
public final class StructureSavedData extends SavedData {
    /** Data file id: {@code <world>/data/crythst/structures.dat}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structures");
    public static final int FORMAT_VERSION = 1;

    public static final SavedDataType<StructureSavedData> TYPE = new SavedDataType<>(
            ID,
            StructureSavedData::new,
            CompoundTag.CODEC.xmap(tag -> new StructureSavedData(tag), StructureSavedData::toTag),
            DataFixTypes.SAVED_DATA_STRUCTURE_FEATURE_INDICES);

    private final Map<UUID, CompoundTag> records = new LinkedHashMap<>();
    private final Map<UUID, PhysicsStructure> live = new LinkedHashMap<>();
    private final Map<PhysicsStructure, UUID> identities = new IdentityHashMap<>();
    private boolean restored;

    public StructureSavedData() {
    }

    private StructureSavedData(CompoundTag root) {
        final ListTag structures = root.getListOrEmpty("Structures");
        for (int i = 0; i < structures.size(); i++) {
            final CompoundTag record = structures.getCompoundOrEmpty(i).copy();
            if (record.isEmpty()) {
                continue;
            }
            UUID id = readId(record);
            if (id == null) {
                id = UUID.randomUUID();
                record.putIntArray("Id", UUIDUtil.uuidToIntArray(id));
            }
            records.put(id, record);
        }
    }

    public CompoundTag toTag() {
        final CompoundTag root = new CompoundTag();
        root.putInt("Version", FORMAT_VERSION);
        final ListTag structures = new ListTag();
        for (CompoundTag record : records.values()) {
            structures.add(record.copy());
        }
        root.put("Structures", structures);
        return root;
    }

    public int structureCount() {
        return records.size();
    }

    public List<UUID> structureIds() {
        return List.copyOf(records.keySet());
    }

    /**
     * Adds or replaces a record. Primarily useful for tests and for tools that
     * import structures without a live physics world.
     */
    public void add(SavedStructure structure) {
        Objects.requireNonNull(structure, "structure");
        records.put(structure.id(), structure.toTag());
        setDirty();
    }

    public void clear() {
        if (records.isEmpty() && live.isEmpty()) {
            return;
        }
        records.clear();
        live.clear();
        identities.clear();
        setDirty();
    }

    public boolean restored() {
        return restored;
    }

    /** Live structures restored by this data object, for diagnostics. */
    public Map<UUID, PhysicsStructure> liveStructures() {
        return Collections.unmodifiableMap(live);
    }

    /**
     * Recreates every saved structure in the given physics world.
     *
     * <p>Safe to call more than once; only the first call does work. A record is
     * skipped when its UUID is already live, or when a live structure has the
     * same bounds and pose (a second restore path may already have created it).</p>
     */
    public void restore(ServerLevel level, LevelPhysics physics) {
        if (restored) {
            return;
        }
        restored = true;
        if (records.isEmpty()) {
            return;
        }
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(physics, "physics");

        final HolderGetter<Block> blocks;
        try {
            blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        } catch (RuntimeException e) {
            Crythst.LOGGER.error("Cannot restore Crythst structures in {}: block registry unavailable",
                    level.dimension().identifier(), e);
            return;
        }

        for (Map.Entry<UUID, CompoundTag> entry : List.copyOf(records.entrySet())) {
            final UUID id = entry.getKey();
            final PhysicsStructure existing = live.get(id);
            if (existing != null && !existing.closed()) {
                continue;
            }
            try {
                final SavedStructure saved = SavedStructure.fromTag(entry.getValue(), blocks);
                final PhysicsStructure alreadyLive = findLive(physics, saved);
                if (alreadyLive != null) {
                    live.put(id, alreadyLive);
                    identities.put(alreadyLive, id);
                    continue;
                }
                final PhysicsStructure structure = physics.createStructure(saved.toStructureDef());
                // createStructure applies the saved pose, but teleport also
                // resets the render interpolation buffer for a clean load.
                structure.teleport(saved.pose());
                applyBlockStates(structure, saved.volume());
                StructureBlockEntityData.put(structure, saved.blockEntities());
                physics.applyBlockEntities(structure, saved.blockEntities());
                live.put(id, structure);
                identities.put(structure, id);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Could not restore Crythst structure {} in {}",
                        id, level.dimension().identifier(), t);
            }
        }
        // The data was just loaded or unchanged; avoid writing it straight back.
        setDirty(false);
    }

    /**
     * Captures every live structure and drops records whose structure no longer
     * exists. Runs on the server thread before the level data is written.
     */
    public void capture(ServerLevel level, LevelPhysics physics) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(physics, "physics");

        final Map<UUID, CompoundTag> captured = new LinkedHashMap<>();
        final Map<PhysicsStructure, UUID> seenIdentities = new IdentityHashMap<>();
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed()) {
                continue;
            }
            UUID id = identities.get(structure);
            if (id == null) {
                id = UUID.randomUUID();
                identities.put(structure, id);
            }
            seenIdentities.put(structure, id);
            try {
                final SavedStructure saved = new SavedStructure(
                        id,
                        structure.pose(),
                        SavedStructureVolume.fromStructure(structure),
                        captureBlockEntities(structure),
                        StructureParameters.from(structure));
                captured.put(id, saved.toTag());
            } catch (Throwable t) {
                Crythst.LOGGER.error("Could not capture Crythst structure #{} in {}",
                        structure.body().id(), level.dimension().identifier(), t);
                // Keep the previous record if one exists so a transient failure
                // does not delete the structure from disk.
                final CompoundTag previous = records.get(id);
                if (previous != null) {
                    captured.put(id, previous.copy());
                }
            }
        }

        // Forget structures that were removed from the physics world. They are
        // normally already closed by LevelPhysics.removeStructure().
        for (Map.Entry<UUID, PhysicsStructure> entry : List.copyOf(live.entrySet())) {
            final PhysicsStructure structure = entry.getValue();
            if (!seenIdentities.containsValue(entry.getKey())) {
                live.remove(entry.getKey());
                if (!structure.closed()) {
                    structure.close();
                }
            }
        }
        identities.keySet().removeIf(structure -> structure.closed()
                || !seenIdentities.containsKey(structure));

        if (!captured.equals(records)) {
            records.clear();
            records.putAll(captured);
            setDirty();
        }
    }

    private PhysicsStructure findLive(LevelPhysics physics, SavedStructure saved) {
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed() || !structure.volume().bounds().equals(saved.volume().bounds())) {
                continue;
            }
            final Transform3d pose = structure.pose();
            if (pose.position().distanceSquared(saved.pose().position()) > 1.0e-12) {
                continue;
            }
            if (Math.abs(quaternionDot(pose, saved.pose())) < 1.0 - 1.0e-9) {
                continue;
            }
            return structure;
        }
        return null;
    }

    private static double quaternionDot(Transform3d left, Transform3d right) {
        final var a = left.rotation();
        final var b = right.rotation();
        return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
    }

    private static void applyBlockStates(PhysicsStructure structure, SavedStructureVolume volume) {
        final IntBounds3 bounds = volume.bounds();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!volume.isSolid(x, y, z)) {
                        continue;
                    }
                    final BlockState state = volume.blockState(x, y, z);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    StructureBlockStates.set(structure, x, y, z, state);
                }
            }
        }
    }

    private static Map<BlockPos, CompoundTag> captureBlockEntities(PhysicsStructure structure) {
        final VoxelVolume volume = structure.volume();
        if (!(volume instanceof BlockVoxelVolume blockVolume)) {
            // A restored structure uses a mutable volume; retain the NBT captured
            // before the restore instead of overwriting it with an empty map.
            return StructureBlockEntityData.get(structure);
        }
        final IntBounds3 bounds = volume.bounds();
        final Map<BlockPos, CompoundTag> result = new LinkedHashMap<>();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!volume.isSolid(x, y, z)) {
                        continue;
                    }
                    // Store structure-local coordinates so the record survives
                    // being placed in a different auxiliary-level region.
                    final BlockPos worldPos = new BlockPos(x, y, z);
                    final BlockPos localPos = new BlockPos(
                            x - bounds.minX(), y - bounds.minY(), z - bounds.minZ());
                    final BlockEntity blockEntity = blockVolume.level().getBlockEntity(worldPos);
                    if (blockEntity == null) {
                        continue;
                    }
                    // Full metadata includes id/x/y/z; custom data alone would
                    // be enough for a future in-place reattachment, but full
                    // metadata makes the captured NBT self-describing.
                    result.put(localPos, blockEntity.saveWithFullMetadata(
                            blockVolume.level().registryAccess()));
                }
            }
        }
        StructureBlockEntityData.put(structure, result);
        return result;
    }

    private static UUID readId(CompoundTag record) {
        final int[] id = record.getIntArray("Id").orElse(null);
        return id != null && id.length == 4 ? UUIDUtil.uuidFromIntArray(id) : null;
    }

    /** All records, unmodifiable, for diagnostics. */
    public List<CompoundTag> records() {
        final List<CompoundTag> copy = new ArrayList<>(records.size());
        for (CompoundTag record : records.values()) {
            copy.add(record.copy());
        }
        return Collections.unmodifiableList(copy);
    }
}
