package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedStructureVolumeTest {
    @Test
    void snapshotRoundTripsSolidsMassAndMaterial() {
        final VoxelVolume original = TestVolumes.asymmetric();
        final SavedStructureVolume saved = SavedStructureVolume.fromVolume(original);
        assertFalse(saved.hasBlockStates(), "a generic volume has no block states");

        final CompoundTag tag = saved.toTag();
        final SavedStructureVolume decoded = SavedStructureVolume.fromTag(tag, null);

        assertBoundsAndCells(original, decoded);
        assertFalse(decoded.hasBlockStates());
        assertEquals(saved.blockMass(1, 1, 0), decoded.blockMass(1, 1, 0), 1.0e-12);
        assertEquals(saved.blockMaterial(1, 1, 0), decoded.blockMaterial(1, 1, 0));
    }

    @Test
    void rebuiltStructurePreservesPoseMassAndCentreOfMass() {
        final VoxelVolume original = TestVolumes.asymmetric();
        final SavedStructureVolume saved = SavedStructureVolume.fromVolume(original);
        final VoxelColliderOptions options = VoxelColliderOptions.DEFAULT
                .withBlockScale(0.5).withGreedyMerge(false);
        final VoxelColliderData before = VoxelColliderBuilder.build(original, options);

        final Transform3d pose = Transform3d.of(
                new Vector3d(12.5, -3.25, 7.75),
                new Quaterniond().rotateY(Math.toRadians(37.0)).rotateZ(Math.toRadians(-11.0)));
        final StructureParameters parameters = new StructureParameters(
                io.mikaple.crythst.physics.body.BodyType.KINEMATIC,
                PhysicsMaterial.METAL,
                new CollisionFilter(2, 0x00FF),
                options,
                1.75,
                0.02,
                0.05,
                true);
        final StructureDef definition = saved.toStructureDef(pose, parameters);

        assertEquals(pose.position(), definition.pose().position());
        assertEquals(pose.rotation(), definition.pose().rotation());
        assertEquals(PhysicsMaterial.METAL, definition.material());
        assertEquals(new CollisionFilter(2, 0x00FF), definition.filter());
        assertEquals(1.75, definition.massScale(), 1.0e-12);
        assertEquals(0.5, definition.colliderOptions().blockScale(), 1.0e-12);
        assertTrue(definition.ccd());

        final VoxelColliderData after = VoxelColliderBuilder.build(definition.volume(),
                definition.colliderOptions());
        // The collider is a direct function of the restored cells, so equal mass
        // and equal centre of mass prove the snapshot kept every voxel.
        assertEquals(before.mass(), after.mass(), 1.0e-9);
        assertEquals(before.centerOfMass().x, after.centerOfMass().x, 1.0e-12);
        assertEquals(before.centerOfMass().y, after.centerOfMass().y, 1.0e-12);
        assertEquals(before.centerOfMass().z, after.centerOfMass().z, 1.0e-12);
        assertEquals(before.boxes().size(), after.boxes().size());
    }

    @Test
    void boundsAndEmptyCellsSurviveNbt() {
        final IntBounds3 bounds = new IntBounds3(-2, 4, 7, 1, 6, 9);
        final VoxelVolume sparse = new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return x == -1 && y == 5 && z == 8;
            }

            @Override
            public double blockMass(int x, int y, int z) {
                return isSolid(x, y, z) ? 42.0 : 0.0;
            }

            @Override
            public PhysicsMaterial blockMaterial(int x, int y, int z) {
                return PhysicsMaterial.ICE;
            }
        };

        final SavedStructureVolume decoded = SavedStructureVolume.fromTag(
                SavedStructureVolume.fromVolume(sparse).toTag(), null);
        assertEquals(bounds, decoded.bounds());
        assertTrue(decoded.isSolid(-1, 5, 8));
        assertFalse(decoded.isSolid(0, 5, 8));
        assertEquals(42.0, decoded.blockMass(-1, 5, 8), 1.0e-12);
        assertEquals(PhysicsMaterial.ICE, decoded.blockMaterial(-1, 5, 8));
        assertNotNull(decoded.toMutableVolume());
    }

    @Test
    void missingSolidArrayIsRejected() {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("Bounds", new int[]{0, 0, 0, 1, 1, 1});
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SavedStructureVolume.fromTag(tag, null));
    }

    private static void assertBoundsAndCells(VoxelVolume expected, VoxelVolume actual) {
        assertEquals(expected.bounds(), actual.bounds());
        final IntBounds3 bounds = expected.bounds();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    assertEquals(expected.isSolid(x, y, z), actual.isSolid(x, y, z),
                            "solid at " + x + "," + y + "," + z);
                    if (expected.isSolid(x, y, z)) {
                        assertEquals(expected.blockMass(x, y, z), actual.blockMass(x, y, z), 1.0e-12,
                                "mass at " + x + "," + y + "," + z);
                        assertEquals(expected.blockMaterial(x, y, z), actual.blockMaterial(x, y, z),
                                "material at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }
}
