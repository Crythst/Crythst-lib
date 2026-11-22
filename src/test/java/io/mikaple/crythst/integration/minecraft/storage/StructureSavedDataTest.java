package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureSavedDataTest {
    @Test
    void nbtCodecRoundTripsRecordsAndStableIdentity() {
        final UUID id = UUID.randomUUID();
        final Transform3d pose = Transform3d.of(
                new Vector3d(-8.0, 64.5, 3.25),
                new Quaterniond().rotateX(Math.toRadians(15.0)).rotateY(Math.toRadians(-72.0)));
        final StructureParameters parameters = new StructureParameters(
                io.mikaple.crythst.physics.body.BodyType.DYNAMIC,
                PhysicsMaterial.WOOD,
                new CollisionFilter(4, 0x000F),
                VoxelColliderOptions.DEFAULT.withBlockScale(2.0),
                1.5,
                0.01,
                0.03,
                true);

        final CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        blockEntity.putInt("LootTableSeed", 17);
        final SavedStructure saved = new SavedStructure(
                id,
                pose,
                SavedStructureVolume.fromVolume(TestVolumes.asymmetric()),
                Map.of(new BlockPos(1, 0, 0), blockEntity),
                parameters);

        final StructureSavedData data = new StructureSavedData();
        data.add(saved);
        data.add(saved); // replacing by UUID must not duplicate
        assertEquals(1, data.structureCount());

        final CompoundTag encoded = (CompoundTag) StructureSavedData.TYPE.codec()
                .encodeStart(NbtOps.INSTANCE, data)
                .getOrThrow();
        final StructureSavedData decoded = StructureSavedData.TYPE.codec()
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();

        assertEquals(1, decoded.structureCount());
        assertEquals(id, decoded.structureIds().get(0));

        final SavedStructure round = SavedStructure.fromTag(decoded.records().get(0), null);
        assertEquals(id, round.id());
        assertEquals(pose.position(), round.pose().position());
        assertEquals(pose.rotation(), round.pose().rotation());
        assertEquals(parameters, round.parameters());
        assertEquals(TestVolumes.BOUNDS, round.volume().bounds());
        assertEquals(1, round.blockEntities().size());
        assertEquals("minecraft:chest",
                round.blockEntities().get(new BlockPos(1, 0, 0)).getStringOr("id", ""));

        final StructureDef definition = round.toStructureDef();
        final VoxelColliderData before = VoxelColliderBuilder.build(TestVolumes.asymmetric());
        final VoxelColliderData after = VoxelColliderBuilder.build(definition.volume());
        assertEquals(before.mass(), after.mass(), 1.0e-9);
        assertEquals(before.centerOfMass().x, after.centerOfMass().x, 1.0e-12);
        assertEquals(before.centerOfMass().y, after.centerOfMass().y, 1.0e-12);
        assertEquals(before.centerOfMass().z, after.centerOfMass().z, 1.0e-12);
    }

    @Test
    void scheduledTicksSurviveNbtRoundTripWithRelativeDelays() {
        final CompoundTag scheduledTicks = new CompoundTag();
        final ListTag blocks = new ListTag();
        final CompoundTag blockTick = new CompoundTag();
        blockTick.putInt("X", 1);
        blockTick.putInt("Y", 2);
        blockTick.putInt("Z", 3);
        blockTick.putString("Type", "minecraft:repeater");
        blockTick.putLong("Delay", 7L);
        blockTick.putInt("Priority", -1);
        blockTick.putLong("Order", 42L);
        blocks.add(blockTick);
        scheduledTicks.put("Block", blocks);

        final ListTag fluids = new ListTag();
        final CompoundTag fluidTick = new CompoundTag();
        fluidTick.putInt("X", -2);
        fluidTick.putInt("Y", 0);
        fluidTick.putInt("Z", 5);
        fluidTick.putString("Type", "minecraft:water");
        fluidTick.putLong("Delay", 3L);
        fluidTick.putInt("Priority", 0);
        fluidTick.putLong("Order", 9L);
        fluids.add(fluidTick);
        scheduledTicks.put("Fluid", fluids);

        final SavedStructure saved = new SavedStructure(
                UUID.randomUUID(),
                Transform3d.IDENTITY,
                SavedStructureVolume.fromVolume(TestVolumes.asymmetric()),
                Map.of(),
                StructureParameters.defaults(),
                scheduledTicks);
        final StructureSavedData data = new StructureSavedData();
        data.add(saved);

        final CompoundTag encoded = (CompoundTag) StructureSavedData.TYPE.codec()
                .encodeStart(NbtOps.INSTANCE, data)
                .getOrThrow();
        final StructureSavedData decoded = StructureSavedData.TYPE.codec()
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();
        final SavedStructure round = SavedStructure.fromTag(decoded.records().get(0), null);

        assertEquals(scheduledTicks, round.scheduledTicks());
        final CompoundTag roundBlock = round.scheduledTicks()
                .getListOrEmpty("Block").getCompoundOrEmpty(0);
        // Delays stay relative to the capture time, not absolute world times.
        assertEquals(7L, roundBlock.getLongOr("Delay", -1L));
        assertEquals(42L, roundBlock.getLongOr("Order", -1L));
    }

    @Test
    void emptyDataRoundTrips() {
        final StructureSavedData data = new StructureSavedData();
        final CompoundTag encoded = (CompoundTag) StructureSavedData.TYPE.codec()
                .encodeStart(NbtOps.INSTANCE, data)
                .getOrThrow();
        final StructureSavedData decoded = StructureSavedData.TYPE.codec()
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();

        assertEquals(0, decoded.structureCount());
        assertFalse(decoded.restored());
        assertTrue(decoded.liveStructures().isEmpty());
    }

    @Test
    void volumeWithoutStatesDoesNotNeedABlockRegistry() {
        final SavedStructureVolume volume = SavedStructureVolume.fromVolume(TestVolumes.asymmetric());
        assertFalse(volume.hasBlockStates());
        final SavedStructureVolume decoded = SavedStructureVolume.fromTag(volume.toTag(), null);
        assertEquals(volume.bounds(), decoded.bounds());
        assertFalse(decoded.isSolid(2, 1, 1));
        assertTrue(decoded.isSolid(2, 0, 1));
    }

    @Test
    void sparseVolumeBoundsSurviveCodec() {
        final IntBounds3 bounds = new IntBounds3(-1, 3, -5, 2, 4, -2);
        final SavedStructureVolume volume = SavedStructureVolume.fromVolume(
                io.mikaple.crythst.physics.voxel.VoxelVolumes.solid(bounds,
                        cell -> cell.x() == 0 && cell.y() == 3 && cell.z() == -3));
        final SavedStructure saved = new SavedStructure(UUID.randomUUID(),
                Transform3d.IDENTITY, volume, Map.of(), StructureParameters.defaults());
        final StructureSavedData data = new StructureSavedData();
        data.add(saved);

        final CompoundTag encoded = (CompoundTag) StructureSavedData.TYPE.codec()
                .encodeStart(NbtOps.INSTANCE, data)
                .getOrThrow();
        final StructureSavedData decoded = StructureSavedData.TYPE.codec()
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();
        final SavedStructure round = SavedStructure.fromTag(decoded.records().get(0), null);

        assertEquals(bounds, round.volume().bounds());
        assertTrue(round.volume().isSolid(0, 3, -3));
        assertFalse(round.volume().isSolid(1, 3, -3));
    }
}
