package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link StructureVoxelView} over a live {@link PhysicsStructure}.
 *
 * <p>The pose is supplied by the caller so server code can use the
 * authoritative body pose and the integrated client can use its interpolated
 * render pose. This view never mutates physics state.</p>
 */
public final class PhysicsStructureView implements StructureVoxelView {
    private final PhysicsStructure structure;
    private final Transform3d pose;

    private PhysicsStructureView(PhysicsStructure structure, Transform3d pose) {
        this.structure = structure;
        this.pose = pose;
    }

    public static PhysicsStructureView of(PhysicsStructure structure, Transform3d pose) {
        return new PhysicsStructureView(structure, pose);
    }

    @Override
    public long bodyId() {
        return structure.body().id();
    }

    @Override
    public Transform3d pose() {
        return pose;
    }

    @Override
    public double blockScale() {
        return structure.blockScale();
    }

    @Override
    public IntBounds3 bounds() {
        return structure.volume().bounds();
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        final VoxelVolume volume = structure.volume();
        return volume.bounds().contains(x, y, z) && volume.isSolid(x, y, z);
    }

    @Override
    public BlockState blockState(int x, int y, int z) {
        final VoxelVolume volume = structure.volume();
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState stored = stateful.blockState(x, y, z);
            if (stored != null && !stored.isAir()) {
                return stored;
            }
        }
        final BlockState sideTable = StructureBlockStates.get(structure, x, y, z);
        return sideTable == null ? Blocks.AIR.defaultBlockState() : sideTable;
    }

    @Override
    public PhysicsStructure structure() {
        return structure;
    }

    @Override
    public boolean closed() {
        return structure.closed();
    }
}
