package io.mikaple.crythst.integration.minecraft.structure.split;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureSplitterTest {
    private static final double BLOCK_MASS = 1000.0;

    @Test
    void fullCubeIsOneComponent() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 3, 3);
        final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(1, components.size());
        final StructureSplitter.Component component = components.get(0);
        assertEquals(27, component.cellCount());
        assertEquals(bounds, component.bounds());
        assertEquals(1, component.boxes().size());
        assertEquals(27, component.boxes().get(0).cellCount());
    }

    @Test
    void twoSeparatedIslandsBecomeTwoComponentsInDeterministicOrder() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 4, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(2, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(2, components.size());
        assertEquals(new StructureSplitter.Cell(0, 0, 0), components.get(0).cells().get(0));
        assertEquals(new StructureSplitter.Cell(2, 0, 0), components.get(1).cells().get(0));
        assertEquals(new IntBounds3(0, 0, 0, 1, 1, 1), components.get(0).bounds());
        assertEquals(new IntBounds3(2, 0, 0, 3, 1, 1), components.get(1).bounds());
    }

    @Test
    void diagonalContactKeepsBlocksTogether() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 1, 2);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 1, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(1, components.size());
        assertEquals(2, components.get(0).cellCount());
    }

    @Test
    void cornerOnlyContactKeepsBlocksTogether() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 2);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 1, 1, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(1, components.size());
        assertEquals(2, components.get(0).cellCount());
    }

    @Test
    void connectedLShapeIsOneComponentWithMultipleBoxes() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(0, 1, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(1, components.size());
        final StructureSplitter.Component component = components.get(0);
        assertEquals(3, component.cellCount());
        assertEquals(bounds, component.bounds());
        assertEquals(2, component.boxes().size(), "greedy merge keeps the L as two boxes");
        assertEquals(3, component.boxes().stream().mapToInt(VoxelBox::cellCount).sum());
    }

    @Test
    void airCellsAreIgnored() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 3, 3);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);

        assertTrue(StructureSplitter.findComponents(volume).isEmpty());
    }

    @Test
    void emptyVolumeAndZeroMassVolumeAreMassless() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 2);
        assertTrue(StructureSplitter.isMassless(VoxelVolumes.empty(bounds)));
        assertTrue(StructureSplitter.isMassless(zeroMassVolume(bounds)));
        assertFalse(StructureSplitter.isMassless(
                VoxelVolumes.filled(bounds, BLOCK_MASS, PhysicsMaterial.STONE)));
    }

    @Test
    void negativeAndOffsetBoundsKeepNeighbourConnectivity() {
        final IntBounds3 bounds = new IntBounds3(-2, 3, -4, 2, 6, -2);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(-2, 3, -4, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(-2, 4, -4, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(1, 5, -3, true, BLOCK_MASS, PhysicsMaterial.STONE);

        final List<StructureSplitter.Component> components = StructureSplitter.findComponents(volume);

        assertEquals(2, components.size());
        assertEquals(2, components.get(0).cellCount());
        assertEquals(new StructureSplitter.Cell(-2, 3, -4), components.get(0).cells().get(0));
        assertEquals(new StructureSplitter.Cell(-2, 4, -4), components.get(0).cells().get(1));
        assertEquals(new StructureSplitter.Cell(1, 5, -3), components.get(1).cells().get(0));
    }

    @Test
    void oversizedVolumeIsRejectedBeforeScanning() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 5000, 5000, 5000);
        final VoxelVolume huge = new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return false;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> StructureSplitter.findComponents(huge));
    }

    @Test
    void splitTicksFollowNonSolidLogicCellsAndPreserveDelayAndOrder() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 7, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(4, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        final List<StructureSplitter.Component> components =
                StructureSplitter.findComponents(volume);

        final Map<StructureSplitter.Cell, Integer> owners = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (StructureSplitter.Cell cell : components.get(i).cells()) {
                owners.put(cell, i);
            }
        }

        final ListTag source = new ListTag();
        // A solid tick and a non-solid tick (for example a repeater or torch)
        // near each component. Non-solid cells used to be dropped entirely.
        source.add(tickTag(0, 0, 0, 3L, TickPriority.NORMAL, 11L));
        source.add(tickTag(1, 0, 0, 7L, TickPriority.HIGH, 12L));
        source.add(tickTag(4, 0, 0, 2L, TickPriority.LOW, 13L));
        source.add(tickTag(5, 0, 0, 9L, TickPriority.NORMAL, 14L));

        final List<ListTag> destinations = new ArrayList<>();
        destinations.add(new ListTag());
        destinations.add(new ListTag());
        StructureSplitter.distributeTicks(source, owners, components, destinations);

        assertEquals(2, destinations.get(0).size());
        assertEquals(2, destinations.get(1).size());
        assertTick(destinations.get(0).getCompoundOrEmpty(0), 0, 0, 0, 3L,
                TickPriority.NORMAL, 11L);
        assertTick(destinations.get(0).getCompoundOrEmpty(1), 1, 0, 0, 7L,
                TickPriority.HIGH, 12L);
        assertTick(destinations.get(1).getCompoundOrEmpty(0), 4, 0, 0, 2L,
                TickPriority.LOW, 13L);
        assertTick(destinations.get(1).getCompoundOrEmpty(1), 5, 0, 0, 9L,
                TickPriority.NORMAL, 14L);
    }

    @Test
    void ownerOfUsesNearestComponentAndNeverDropsUnresolvedCells() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 9, 1, 1);
        final MutableVoxelVolume volume = VoxelVolumes.empty(bounds);
        volume.setBlock(0, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        volume.setBlock(8, 0, 0, true, BLOCK_MASS, PhysicsMaterial.STONE);
        final List<StructureSplitter.Component> components =
                StructureSplitter.findComponents(volume);

        assertEquals(0, StructureSplitter.ownerOf(new StructureSplitter.Cell(1, 0, 0), components));
        assertEquals(1, StructureSplitter.ownerOf(new StructureSplitter.Cell(7, 0, 0), components));
        // Equidistant cell: deterministic lowest component index.
        assertEquals(0, StructureSplitter.ownerOf(new StructureSplitter.Cell(4, 0, 0), components));
    }

    private static CompoundTag tickTag(int x, int y, int z, long delay,
                                       TickPriority priority, long order) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putInt("Z", z);
        tag.putString("Type", "minecraft:repeater");
        tag.putLong("Delay", delay);
        tag.putInt("Priority", priority.getValue());
        tag.putLong("Order", order);
        return tag;
    }

    private static void assertTick(CompoundTag tag, int x, int y, int z, long delay,
                                   TickPriority priority, long order) {
        assertEquals(x, tag.getIntOr("X", 0));
        assertEquals(y, tag.getIntOr("Y", 0));
        assertEquals(z, tag.getIntOr("Z", 0));
        assertEquals(delay, tag.getLongOr("Delay", -1L));
        assertEquals(priority.getValue(), tag.getIntOr("Priority", 99));
        assertEquals(order, tag.getLongOr("Order", -1L));
    }

    private static VoxelVolume zeroMassVolume(IntBounds3 bounds) {
        return new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return bounds.contains(x, y, z);
            }

            @Override
            public double blockMass(int x, int y, int z) {
                return 0.0;
            }
        };
    }
}
