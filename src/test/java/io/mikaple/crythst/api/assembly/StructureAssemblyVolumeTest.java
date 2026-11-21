package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAssemblyVolumeTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void volumeIsZeroBasedAndKeepsStateMassAndMaterial() {
        final Map<BlockPos, StructureAssembly.BlockSnapshot> snapshots = new HashMap<>();
        final BlockPos a = new BlockPos(10, 64, -5);
        final BlockPos b = new BlockPos(11, 64, -5);
        final BlockPos c = new BlockPos(12, 65, -4);
        snapshots.put(a, new StructureAssembly.BlockSnapshot(
                Blocks.STONE.defaultBlockState(), 1000.0, PhysicsMaterial.STONE, null));
        snapshots.put(b, new StructureAssembly.BlockSnapshot(
                Blocks.OAK_PLANKS.defaultBlockState(), 250.0, PhysicsMaterial.WOOD, null));
        snapshots.put(c, new StructureAssembly.BlockSnapshot(
                Blocks.ICE.defaultBlockState(), 900.0, PhysicsMaterial.ICE, null));

        final StructureAssembly.BuiltVolume built =
                StructureAssembly.buildVolume(List.of(a, b, c), snapshots::get);

        assertEquals(a, built.origin());
        assertEquals(new io.mikaple.crythst.physics.voxel.IntBounds3(0, 0, 0, 3, 2, 2),
                built.volume().bounds());
        assertTrue(built.volume().isSolid(0, 0, 0));
        assertFalse(built.volume().isSolid(1, 1, 0));
        assertEquals(250.0, built.volume().blockMass(1, 0, 0), 1.0e-12);
        assertEquals(PhysicsMaterial.WOOD, built.volume().blockMaterial(1, 0, 0));
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(),
                built.volume().blockState(1, 0, 0));
        assertEquals(List.of(a, b, c), built.worldBlocks());
    }

    @Test
    void blockEntitiesAreKeyedByBoundsRelativeVoxel() {
        final BlockPos origin = new BlockPos(5, 70, 5);
        final BlockPos chest = new BlockPos(6, 70, 5);
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        tag.putInt("Count", 3);
        final StructureAssembly.BlockSnapshot snapshot = new StructureAssembly.BlockSnapshot(
                Blocks.CHEST.defaultBlockState(), 400.0, PhysicsMaterial.WOOD, tag);
        final StructureAssembly.BlockSnapshot stone = new StructureAssembly.BlockSnapshot(
                Blocks.STONE.defaultBlockState(), 1000.0, PhysicsMaterial.STONE, null);

        final StructureAssembly.BuiltVolume built = StructureAssembly.buildVolume(
                List.of(origin, chest), pos -> pos.equals(chest) ? snapshot : stone);

        assertEquals(new BlockPos(5, 70, 5), built.origin());
        assertEquals(1, built.blockEntities().size());
        final CompoundTag stored = built.blockEntities().get(new BlockPos(1, 0, 0));
        assertNotNull(stored);
        assertEquals("minecraft:chest", stored.getStringOr("id", ""));
        assertEquals(3, stored.getIntOr("Count", 0));
    }

    @Test
    void duplicatesAndUnsupportedPositionsAreSkipped() {
        final BlockPos a = new BlockPos(0, 0, 0);
        final BlockPos b = new BlockPos(1, 0, 0);
        final Map<BlockPos, StructureAssembly.BlockSnapshot> snapshots = new HashMap<>();
        snapshots.put(a, new StructureAssembly.BlockSnapshot(
                Blocks.STONE.defaultBlockState(), 1000.0, PhysicsMaterial.STONE, null));

        final StructureAssembly.BuiltVolume built = StructureAssembly.buildVolume(
                List.of(a, a, b, new BlockPos(2, 0, 0)), snapshots::get);

        assertEquals(1, built.worldBlocks().size());
        assertEquals(a, built.origin());
    }

    @Test
    void emptyOrAllUnsupportedSetsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StructureAssembly.buildVolume(List.of(), pos -> null));
        assertThrows(IllegalArgumentException.class,
                () -> StructureAssembly.buildVolume(List.of(new BlockPos(0, 0, 0)), pos -> null));
    }

    @Test
    void anchorPoseKeepsIdentityAssemblyAtTheOriginalMinimum() {
        final BlockPos anchor = new BlockPos(10, 64, -5);
        final BlockPos origin = new BlockPos(12, 66, -3);
        final Transform3d pose = StructureAssembly.structurePose(
                anchor, origin, AssemblyOptions.defaults());

        assertEquals(new Vector3d(12.0, 66.0, -3.0), pose.position());
        assertEquals(new Quaterniond(), pose.rotation());

        final BlockPos mapped = new AssemblyTransform(pose, 1.0)
                .voxelToWorldBlock(0, 0, 0);
        assertEquals(origin, mapped);
    }

    @Test
    void rotationIsAppliedAroundTheAnchor() {
        final BlockPos anchor = new BlockPos(10, 64, -5);
        final BlockPos origin = new BlockPos(12, 66, -3);
        final AssemblyOptions options = AssemblyOptions.defaults()
                .withPose(Transform3d.of(new Vector3d(),
                        new Quaterniond().rotateY(Math.PI / 2.0)));

        final Transform3d pose = StructureAssembly.structurePose(anchor, origin, options);
        // anchor + rotateY90(origin - anchor): (2,2,2) becomes (2,2,-2).
        assertEquals(new Vector3d(12.0, 66.0, -7.0), pose.position());
    }
}
