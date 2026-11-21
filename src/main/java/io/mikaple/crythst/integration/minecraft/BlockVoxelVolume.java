package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A {@link VoxelVolume} view over a Minecraft level region.
 *
 * <p>Coordinates are absolute block coordinates. With an identity structure
 * pose, block {@code (x,y,z)} occupies the metre cube beginning at that world
 * position, which matches Minecraft's block grid.</p>
 */
public final class BlockVoxelVolume implements StatefulVoxelVolume {
    private final Level level;
    private final IntBounds3 bounds;
    private final BlockPhysicsMapper mapper;

    public BlockVoxelVolume(Level level, IntBounds3 bounds, BlockPhysicsMapper mapper) {
        this.level = Validate.notNull(level, "level");
        this.bounds = Validate.notNull(bounds, "bounds");
        this.mapper = Validate.notNull(mapper, "mapper");
    }

    public Level level() {
        return level;
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        final BlockPos pos = new BlockPos(x, y, z);
        final BlockState state = stateAt(x, y, z);
        if (state.isAir()) {
            return false;
        }
        // Most structures are made of full blocks; avoid extracting a
        // VoxelShape for them. Non-full shapes still use the precise test.
        return state.isCollisionShapeFullBlock(level, pos)
                || !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Raw block state at a voxel position, used when copying structure blocks
     * into the auxiliary ticking level.
     */
    public BlockState blockState(int x, int y, int z) {
        return stateAt(x, y, z);
    }

    @Override
    public double blockMass(int x, int y, int z) {
        return mapper.properties(stateAt(x, y, z)).mass();
    }

    @Override
    public PhysicsMaterial blockMaterial(int x, int y, int z) {
        return mapper.properties(stateAt(x, y, z)).material();
    }

    private BlockState stateAt(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Block " + x + "," + y + "," + z + " outside " + bounds);
        }
        return level.getBlockState(new BlockPos(x, y, z));
    }
}
