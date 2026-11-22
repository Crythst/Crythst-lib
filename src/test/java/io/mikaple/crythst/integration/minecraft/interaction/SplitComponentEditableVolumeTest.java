package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.integration.minecraft.EditableStatefulVolume;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "a structure becomes unbreakable once it splits into two
 * or more bodies".
 *
 * <p>A split assembles each component from a stateful wrapper volume, which is
 * not a {@code MutableVoxelVolume}. The interaction layer therefore has to
 * accept {@link EditableStatefulVolume}; otherwise every edit path returns
 * {@code FAILED} for the new bodies.</p>
 */
class SplitComponentEditableVolumeTest {
    private static final double BLOCK_MASS = 1000.0;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Two separated single-voxel islands, so the split yields two components. */
    private static MutableVoxelVolume twoIslands() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 4, 1, 1);
        final MutableVoxelVolume source = VoxelVolumes.empty(bounds);
        source.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        source.setBlock(2, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        return source;
    }

    @Test
    void splitComponentVolumeStaysEditableForInteraction() {
        final MutableVoxelVolume source = twoIslands();
        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(source);
        assertEquals(2, components.size());

        final VoxelVolume componentVolume =
                StructureSplitter.componentVolume(source, components.get(0));

        assertTrue(componentVolume instanceof EditableStatefulVolume,
                "a split component volume must keep carrying block states");
        final StructureInteraction.EditableVolume editable =
                StructureInteraction.editableVolume(componentVolume);
        assertNotNull(editable,
                "a split component must stay editable, otherwise it cannot be broken or built on");

        // Writing through the interaction adapter must clear both the collision
        // cell and the recorded state, exactly like breaking a block.
        editable.setBlock(0, 0, 0, false, 0.0, PhysicsMaterial.DEFAULT, null);
        assertFalse(componentVolume.isSolid(0, 0, 0));
        assertNull(((EditableStatefulVolume) componentVolume).blockState(0, 0, 0));
    }

    @Test
    void splitComponentVolumeKeepsPlacedStates() {
        final MutableVoxelVolume source = twoIslands();
        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(source);
        final EditableStatefulVolume volume = (EditableStatefulVolume)
                StructureSplitter.componentVolume(source, components.get(0));

        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE,
                Blocks.STONE.defaultBlockState());
        assertTrue(volume.isSolid(0, 0, 0));
        assertEquals(Blocks.STONE.defaultBlockState(), volume.blockState(0, 0, 0));

        volume.setBlock(0, 0, 0, false, 0.0, PhysicsMaterial.DEFAULT, null);
        assertFalse(volume.isSolid(0, 0, 0));
        assertNull(volume.blockState(0, 0, 0));
    }

    @Test
    void splitComponentVolumeKeepsNonCollidingLogicBlockStates() {
        final MutableVoxelVolume source = twoIslands();
        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(source);
        final EditableStatefulVolume volume = (EditableStatefulVolume)
                StructureSplitter.componentVolume(source, components.get(0));

        // Non-colliding logic blocks stay non-solid but must keep their state so
        // the auxiliary ticking level can rebuild them.
        volume.setBlock(1, 0, 0, false, 0.0, PhysicsMaterial.DEFAULT,
                Blocks.REPEATER.defaultBlockState());
        assertFalse(volume.isSolid(1, 0, 0));
        assertEquals(Blocks.REPEATER.defaultBlockState(), volume.blockState(1, 0, 0));
    }
}
