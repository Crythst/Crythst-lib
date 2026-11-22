package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Block;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One persisted physics structure: stable identity, pose, voxel snapshot, block
 * entity data and structure parameters.
 *
 * <p>Block entity coordinates are structure-local voxels, not world positions.
 * They are kept as opaque NBT because a restored structure is not inserted back
 * into the Minecraft block grid; see {@code docs/structure-persistence.md}.</p>
 */
public record SavedStructure(
        UUID id,
        Transform3d pose,
        SavedStructureVolume volume,
        Map<BlockPos, CompoundTag> blockEntities,
        StructureParameters parameters,
        CompoundTag scheduledTicks
) {
    public SavedStructure {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(parameters, "parameters");
        final Map<BlockPos, CompoundTag> copied = new LinkedHashMap<>();
        if (blockEntities != null) {
            for (Map.Entry<BlockPos, CompoundTag> entry : blockEntities.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                copied.put(entry.getKey().immutable(), entry.getValue().copy());
            }
        }
        blockEntities = Collections.unmodifiableMap(copied);
        scheduledTicks = scheduledTicks == null ? new CompoundTag() : scheduledTicks.copy();
    }

    /** Backward-compatible constructor without pending scheduled ticks. */
    public SavedStructure(UUID id, Transform3d pose, SavedStructureVolume volume,
                          Map<BlockPos, CompoundTag> blockEntities,
                          StructureParameters parameters) {
        this(id, pose, volume, blockEntities, parameters, new CompoundTag());
    }

    public StructureDef toStructureDef() {
        return volume.toStructureDef(pose, parameters);
    }

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("Id", UUIDUtil.uuidToIntArray(id));

        final CompoundTag poseTag = new CompoundTag();
        final Vector3d position = pose.position();
        poseTag.putDouble("X", position.x);
        poseTag.putDouble("Y", position.y);
        poseTag.putDouble("Z", position.z);
        final Quaterniond rotation = pose.rotation();
        poseTag.putDouble("QX", rotation.x);
        poseTag.putDouble("QY", rotation.y);
        poseTag.putDouble("QZ", rotation.z);
        poseTag.putDouble("QW", rotation.w);
        tag.put("Pose", poseTag);

        tag.put("Volume", volume.toTag());
        tag.put("Parameters", parameters.toTag());

        final ListTag blockEntityTags = new ListTag();
        for (Map.Entry<BlockPos, CompoundTag> entry : blockEntities.entrySet()) {
            final CompoundTag blockEntityTag = new CompoundTag();
            final BlockPos pos = entry.getKey();
            blockEntityTag.putInt("X", pos.getX());
            blockEntityTag.putInt("Y", pos.getY());
            blockEntityTag.putInt("Z", pos.getZ());
            blockEntityTag.put("Data", entry.getValue().copy());
            blockEntityTags.add(blockEntityTag);
        }
        tag.put("BlockEntities", blockEntityTags);
        if (!scheduledTicks.isEmpty()) {
            tag.put("ScheduledTicks", scheduledTicks.copy());
        }
        return tag;
    }

    /**
     * @param blocks block registry lookup for volume block states; may be
     *               {@code null} only for volumes without states
     */
    public static SavedStructure fromTag(CompoundTag tag, HolderGetter<Block> blocks) {
        Validate.notNull(tag, "tag");
        final int[] idArray = tag.getIntArray("Id").orElse(null);
        final UUID id = idArray != null && idArray.length == 4
                ? UUIDUtil.uuidFromIntArray(idArray) : UUID.randomUUID();

        final CompoundTag poseTag = tag.getCompoundOrEmpty("Pose");
        final Transform3d pose = poseTag.isEmpty()
                ? Transform3d.IDENTITY
                : Transform3d.of(
                        new Vector3d(poseTag.getDoubleOr("X", 0.0),
                                poseTag.getDoubleOr("Y", 0.0),
                                poseTag.getDoubleOr("Z", 0.0)),
                        new Quaterniond(poseTag.getDoubleOr("QX", 0.0),
                                poseTag.getDoubleOr("QY", 0.0),
                                poseTag.getDoubleOr("QZ", 0.0),
                                poseTag.getDoubleOr("QW", 1.0)));

        final CompoundTag volumeTag = tag.getCompoundOrEmpty("Volume");
        if (volumeTag.isEmpty()) {
            throw new IllegalArgumentException("Saved structure " + id + " has no Volume");
        }
        final SavedStructureVolume volume = SavedStructureVolume.fromTag(volumeTag, blocks);

        final Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        final ListTag blockEntityTags = tag.getListOrEmpty("BlockEntities");
        for (int i = 0; i < blockEntityTags.size(); i++) {
            final CompoundTag entry = blockEntityTags.getCompoundOrEmpty(i);
            if (entry.isEmpty()) {
                continue;
            }
            final BlockPos pos = new BlockPos(entry.getIntOr("X", 0),
                    entry.getIntOr("Y", 0), entry.getIntOr("Z", 0));
            blockEntities.put(pos, entry.getCompoundOrEmpty("Data"));
        }

        final StructureParameters parameters = StructureParameters.fromTag(
                tag.getCompoundOrEmpty("Parameters"));
        return new SavedStructure(id, pose, volume, blockEntities, parameters,
                tag.getCompoundOrEmpty("ScheduledTicks"));
    }
}
