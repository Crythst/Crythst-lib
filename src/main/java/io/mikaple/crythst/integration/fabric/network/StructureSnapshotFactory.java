package io.mikaple.crythst.integration.fabric.network;

import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSyncLimits;
import io.mikaple.crythst.network.structure.StructureTooLargeException;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a server-side {@link PhysicsStructure} into a wire snapshot.
 *
 * <p>{@link BlockVoxelVolume} preserves the real block states. Other volumes
 * (for example {@code MutableVoxelVolume} built by gameplay code) only expose
 * solidity, so their solid cells are sent as stone. Block entity data is not
 * included in protocol version 1.</p>
 */
public final class StructureSnapshotFactory {
    private StructureSnapshotFactory() {
    }

    public static StructureSnapshot capture(PhysicsStructure structure) {
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        final long volumeCells = bounds.volume();
        if (volumeCells > StructureSyncLimits.MAX_CELLS) {
            throw new StructureTooLargeException("Structure body #" + structure.body().id()
                    + " has " + volumeCells + " voxels, limit is " + StructureSyncLimits.MAX_CELLS);
        }

        final int[] cells = new int[(int) volumeCells];
        final List<Integer> palette = new ArrayList<>();
        final Map<Integer, Integer> paletteIndex = new HashMap<>();
        final BlockVoxelVolume blockVolume = volume instanceof BlockVoxelVolume candidate ? candidate : null;
        final boolean hasSideTableStates = volume instanceof MutableVoxelVolume
                && StructureBlockStates.size(structure) > 0;
        final int fallbackStateId = Block.getId(Blocks.STONE.defaultBlockState());

        final int sizeX = bounds.sizeX();
        final int sizeY = bounds.sizeY();
        final int sizeZ = bounds.sizeZ();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!volume.isSolid(x, y, z)) {
                        continue;
                    }
                    final BlockState state = blockVolume != null
                            ? blockVolume.blockState(x, y, z)
                            : hasSideTableStates
                                    ? StructureBlockStates.get(structure, x, y, z)
                                    : null;
                    final int stateId = state == null || state.isAir()
                            ? fallbackStateId
                            : Block.getId(state);
                    Integer index = paletteIndex.get(stateId);
                    if (index == null) {
                        if (palette.size() >= StructureSyncLimits.MAX_PALETTE) {
                            throw new StructureTooLargeException("Structure body #" + structure.body().id()
                                    + " uses more than " + StructureSyncLimits.MAX_PALETTE
                                    + " distinct block states");
                        }
                        index = palette.size() + 1;
                        palette.add(stateId);
                        paletteIndex.put(stateId, index);
                    }
                    final int localX = x - bounds.minX();
                    final int localY = y - bounds.minY();
                    final int localZ = z - bounds.minZ();
                    final int cellIndex = (localY * sizeZ + localZ) * sizeX + localX;
                    cells[cellIndex] = index;
                }
            }
        }

        final int[] paletteArray = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            paletteArray[i] = palette.get(i);
        }

        final Vector3d centerOfMass = structure.collider().centerOfMass();
        return new StructureSnapshot(
                structure.body().id(),
                structure.pose(),
                bounds,
                paletteArray,
                cells,
                structure.blockScale(),
                structure.massScale(),
                structure.collider().mass(),
                centerOfMass
        );
    }
}
