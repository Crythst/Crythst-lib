package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Dense assembly volume backed by a {@link MutableVoxelVolume} plus the block
 * state for each cell.
 *
 * <p>{@link MutableVoxelVolume} intentionally stays Minecraft-free, so it
 * cannot store block states itself. The assembly API wraps it here instead of
 * using a side table: collider hooks, the auxiliary ticking level and the
 * persistence snapshot all see the real states during the first structure
 * build, not after a second refresh.</p>
 */
final class AssemblyVolume implements StatefulVoxelVolume {
    private final IntBounds3 bounds;
    private final MutableVoxelVolume blocks;
    private final BlockState[] states;

    AssemblyVolume(IntBounds3 bounds) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.blocks = new MutableVoxelVolume(bounds);
        this.states = new BlockState[Math.toIntExact(bounds.volume())];
    }

    void setBlock(int x, int y, int z, double mass, PhysicsMaterial material, BlockState state) {
        blocks.setBlock(x, y, z, true, mass, material);
        states[index(x, y, z)] = state;
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return bounds.contains(x, y, z) && blocks.isSolid(x, y, z);
    }

    @Override
    public double blockMass(int x, int y, int z) {
        return blocks.blockMass(x, y, z);
    }

    @Override
    public PhysicsMaterial blockMaterial(int x, int y, int z) {
        return blocks.blockMaterial(x, y, z);
    }

    @Override
    public BlockState blockState(int x, int y, int z) {
        if (!bounds.contains(x, y, z) || !blocks.isSolid(x, y, z)) {
            return null;
        }
        return states[index(x, y, z)];
    }

    MutableVoxelVolume mutableVolume() {
        return blocks;
    }

    private int index(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException(
                    "Voxel " + x + "," + y + "," + z + " outside " + bounds);
        }
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }
}
