package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interaction layer edits restored volumes in place. This test locks in the
 * edit surface without needing a Minecraft level or a physics backend.
 */
class RestoredStructureVolumeEditTest {
    @Test
    void restoredVolumeSupportsBreakAndPlaceEdits() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 2);
        final MutableVoxelVolume source = VoxelVolumes.filled(
                bounds, 750.0, PhysicsMaterial.STONE);
        final SavedStructureVolume saved = SavedStructureVolume.fromVolume(source);
        final RestoredStructureVolume restored = new RestoredStructureVolume(saved);

        assertTrue(restored.isSolid(0, 0, 0));
        restored.setBlock(0, 0, 0, false, 0.0, PhysicsMaterial.DEFAULT, null);
        assertFalse(restored.isSolid(0, 0, 0));

        restored.setBlock(1, 1, 1, true, 321.0, PhysicsMaterial.METAL, null);
        assertTrue(restored.isSolid(1, 1, 1));
        assertEquals(321.0, restored.blockMass(1, 1, 1), 1.0e-9);
        assertEquals(PhysicsMaterial.METAL, restored.blockMaterial(1, 1, 1));
    }
}
