package io.mikaple.crythst.api.schematic;

import io.mikaple.crythst.integration.minecraft.storage.SavedStructureVolume;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaStructureKeepsStatesBlockEntitiesAndNonSolidBlocks() {
        final Schematic schematic = Schematic.load(vanillaTag(), BuiltInRegistries.BLOCK);

        assertEquals(Schematic.Format.VANILLA_STRUCTURE, schematic.format());
        assertEquals(new IntBounds3(0, 0, 0, 3, 2, 2), schematic.bounds());
        assertEquals(3, schematic.sizeX());
        assertEquals(2, schematic.sizeY());
        assertEquals(4, schematic.nonAirBlockCount());
        assertEquals(3, schematic.solidBlockCount());
        assertEquals(4, schematic.palette().size());

        final BlockState stairs = schematic.blockStateAt(1, 0, 0);
        assertNotNull(stairs);
        assertEquals(Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST), stairs);

        final BlockState wire = schematic.blockStateAt(0, 1, 0);
        assertNotNull(wire);
        assertEquals(Blocks.REDSTONE_WIRE, wire.getBlock());
        assertFalse(schematic.volume().isSolid(0, 1, 0),
                "logic blocks remain in the volume but do not collide");

        assertEquals(1, schematic.blockEntities().size());
        final CompoundTag chestNbt = schematic.blockEntities()
                .get(new net.minecraft.core.BlockPos(2, 0, 0));
        assertNotNull(chestNbt);
        assertEquals("minecraft:chest", chestNbt.getStringOr("id", ""));
        assertEquals(7, chestNbt.getIntOr("Custom", 0));

        // The accessor is defensive: mutating the returned tag must not change
        // the decoded schematic used for instantiation.
        chestNbt.putInt("Custom", 99);
        assertEquals(7, schematic.blockEntities()
                .get(new net.minecraft.core.BlockPos(2, 0, 0))
                .getIntOr("Custom", 0));
    }

    @Test
    void toTagRoundTripsThroughVanillaFormat() {
        final Schematic original = Schematic.load(vanillaTag(), BuiltInRegistries.BLOCK);
        final CompoundTag written = original.toTag();
        final Schematic decoded = Schematic.load(written, BuiltInRegistries.BLOCK);

        assertEquals(Schematic.Format.VANILLA_STRUCTURE, decoded.format());
        assertEquals(original.bounds(), decoded.bounds());
        assertEquals(original.nonAirBlockCount(), decoded.nonAirBlockCount());
        assertEquals(original.solidBlockCount(), decoded.solidBlockCount());
        assertEquals(new java.util.HashSet<>(original.palette()),
                new java.util.HashSet<>(decoded.palette()));
        assertEquals(original.blockEntities().size(), decoded.blockEntities().size());
        assertEquals(original.blockStateAt(0, 1, 0), decoded.blockStateAt(0, 1, 0));
        assertEquals(original.blockStateAt(1, 0, 0), decoded.blockStateAt(1, 0, 0));
    }

    @Test
    void structureDefAppliesPoseAndScale() {
        final Schematic schematic = Schematic.load(vanillaTag(), BuiltInRegistries.BLOCK);
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, -4.0, 2.5),
                new Quaterniond().rotateY(Math.toRadians(37.0)));
        final SchematicOptions options = SchematicOptions.DEFAULTS
                .withPose(pose)
                .withBlockScale(0.5)
                .withMassScale(2.0);

        final StructureDef definition = schematic.toStructureDef(options);
        assertEquals(pose, definition.pose());
        assertEquals(0.5, definition.colliderOptions().blockScale(), 1.0e-12);
        assertEquals(2.0, definition.massScale(), 1.0e-12);
        assertEquals(schematic.volume(), definition.volume());
    }

    @Test
    void sizeAndBlockLimitsAreEnforcedBeforeInstantiation() {
        final SchematicLimits tooSmall =
                new SchematicLimits(2, 8, 8, 128, 128, 1L << 20);
        assertThrows(SchematicException.class,
                () -> Schematic.load(vanillaTag(), BuiltInRegistries.BLOCK, tooSmall));

        final SchematicLimits tooManyBlocks =
                new SchematicLimits(8, 8, 8, 128, 2, 1L << 20);
        assertThrows(SchematicException.class,
                () -> Schematic.load(vanillaTag(), BuiltInRegistries.BLOCK, tooManyBlocks));
    }

    @Test
    void crythstVolumeDefinitionLoadsSolidsAndMass() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 1, 1);
        final MutableVoxelVolume source = new MutableVoxelVolume(bounds);
        source.setBlock(0, 0, 0, true, 321.0, PhysicsMaterial.METAL);
        final SavedStructureVolume saved = SavedStructureVolume.fromVolume(source);

        final Schematic schematic = Schematic.load(saved.toTag(), BuiltInRegistries.BLOCK);
        assertEquals(Schematic.Format.CRYTHST_VOLUME, schematic.format());
        assertEquals(bounds, schematic.bounds());
        assertEquals(1, schematic.nonAirBlockCount());
        assertEquals(1, schematic.solidBlockCount());
        assertTrue(schematic.volume().isSolid(0, 0, 0));
        assertEquals(321.0, schematic.volume().blockMass(0, 0, 0), 1.0e-12);
        assertEquals(PhysicsMaterial.METAL, schematic.volume().blockMaterial(0, 0, 0));
    }

    @Test
    void malformedAndUnsupportedFormatsAreRejected() {
        final CompoundTag missingSize = new CompoundTag();
        missingSize.put("palette", new ListTag());
        assertThrows(SchematicException.class,
                () -> Schematic.load(missingSize, BuiltInRegistries.BLOCK));

        final CompoundTag sponge = new CompoundTag();
        sponge.putInt("Width", 2);
        sponge.putInt("Height", 2);
        sponge.putInt("Length", 2);
        sponge.put("BlockData", new net.minecraft.nbt.ByteArrayTag(new byte[]{1, 2, 3}));
        final SchematicException failure = assertThrows(SchematicException.class,
                () -> Schematic.load(sponge, BuiltInRegistries.BLOCK));
        assertTrue(failure.getMessage().contains("Sponge"));
    }

    private static CompoundTag vanillaTag() {
        final CompoundTag tag = new CompoundTag();
        tag.put("size", intList(3, 2, 2));

        final ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)));
        palette.add(NbtUtils.writeBlockState(Blocks.REDSTONE_WIRE.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));
        tag.put("palette", palette);

        final ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0, null));
        blocks.add(block(1, 0, 0, 1, null));
        blocks.add(block(0, 1, 0, 2, null));
        final CompoundTag chestNbt = new CompoundTag();
        chestNbt.putString("id", "minecraft:chest");
        chestNbt.putInt("Custom", 7);
        blocks.add(block(2, 0, 0, 3, chestNbt));
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        return tag;
    }

    private static CompoundTag block(int x, int y, int z, int state,
                                     CompoundTag nbt) {
        final CompoundTag tag = new CompoundTag();
        tag.put("pos", intList(x, y, z));
        tag.putInt("state", state);
        if (nbt != null) {
            tag.put("nbt", nbt.copy());
        }
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
