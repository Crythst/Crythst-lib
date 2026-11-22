package io.mikaple.crythst.integration.minecraft.boundary;

import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Cached shell of a structure volume.
 *
 * <p>The index contains every face where a volume cell has a neighbour outside
 * the volume bounds. Internal cavity faces are not included: they never touch
 * the host world. The shell only depends on the voxel bounds and content
 * revision, not on the moving pose, so a structure can slide/tumble without
 * rebuilding it. Host positions are computed on demand through
 * {@link BoundaryFaceMapping}.</p>
 */
public final class StructureBoundaryIndex {
    private final IntBounds3 bounds;
    private final long contentRevision;
    private final List<BoundaryFace> faces;

    private StructureBoundaryIndex(IntBounds3 bounds, long contentRevision,
                                   List<BoundaryFace> faces) {
        this.bounds = bounds;
        this.contentRevision = contentRevision;
        this.faces = List.copyOf(faces);
    }

    public static StructureBoundaryIndex build(PhysicsStructure structure) {
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        final List<BoundaryFace> faces = new ArrayList<>();
        // The external shell is purely geometric. Generate the six bound
        // planes directly instead of scanning the whole volume; a 64^3 solid
        // structure then costs ~24k faces, not 262k interior cells.
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                faces.add(new BoundaryFace(bounds.minX(), y, z, Direction.WEST));
                faces.add(new BoundaryFace(bounds.maxX() - 1, y, z, Direction.EAST));
            }
        }
        for (int x = bounds.minX(); x < bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                faces.add(new BoundaryFace(x, bounds.minY(), z, Direction.DOWN));
                faces.add(new BoundaryFace(x, bounds.maxY() - 1, z, Direction.UP));
            }
        }
        for (int x = bounds.minX(); x < bounds.maxX(); x++) {
            for (int y = bounds.minY(); y < bounds.maxY(); y++) {
                faces.add(new BoundaryFace(x, y, bounds.minZ(), Direction.NORTH));
                faces.add(new BoundaryFace(x, y, bounds.maxZ() - 1, Direction.SOUTH));
            }
        }
        return new StructureBoundaryIndex(bounds, structure.contentRevision(), faces);
    }

    public IntBounds3 bounds() {
        return bounds;
    }

    public long contentRevision() {
        return contentRevision;
    }

    public List<BoundaryFace> faces() {
        return faces;
    }

    public boolean matches(PhysicsStructure structure) {
        // The shell is geometric: every cell (including air) contributes its
        // outward faces. Redstone/fluid state changes therefore do not change
        // it; only a volume-bound change (edit expansion, split) does.
        return bounds.equals(structure.volume().bounds());
    }

    /**
     * Live or side-table state for a structure voxel. Fluid cells and redstone
     * components are deliberately included even though they have no collision
     * shape.
     *
     * @return the state, or {@code null} for an empty cell
     */
    public static @Nullable BlockState stateAt(PhysicsStructure structure, int x, int y, int z) {
        if (structure == null || structure.closed()) {
            return null;
        }
        final VoxelVolume volume = structure.volume();
        if (!volume.bounds().contains(x, y, z)) {
            return null;
        }
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null) {
                return state.isAir() ? null : state;
            }
        }
        if (volume instanceof BlockVoxelVolume blockVolume) {
            final BlockState state = blockVolume.blockState(x, y, z);
            if (state != null) {
                return state.isAir() ? null : state;
            }
        }
        final BlockState side = StructureBlockStates.get(structure, x, y, z);
        if (side != null) {
            return side.isAir() ? null : side;
        }
        return volume.isSolid(x, y, z) ? Blocks.STONE.defaultBlockState() : null;
    }

    /** Whether a neighbouring local position is part of the structure volume. */
    public static boolean insideBounds(IntBounds3 bounds, int x, int y, int z) {
        return bounds.contains(x, y, z);
    }
}
