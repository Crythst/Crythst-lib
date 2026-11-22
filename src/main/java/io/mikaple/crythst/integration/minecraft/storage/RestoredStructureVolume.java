package io.mikaple.crythst.integration.minecraft.storage;

import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Editable, stateful volume for restored structures and split components.
 *
 * <p>Keeping the block states on the volume itself is important: the auxiliary
 * ticking level copies blocks during {@code PhysicsStructure} construction,
 * before the persistence layer gets a chance to populate a side table.</p>
 */
public final class RestoredStructureVolume implements StatefulVoxelVolume {
    private final IntBounds3 bounds;
    private final boolean[] solid;
    private final double[] mass;
    private final PhysicsMaterial[] materials;
    /** Lazily populated overrides; the source snapshot provides the base state. */
    private final BlockState[] states;
    private final @Nullable SavedStructureVolume source;

    public RestoredStructureVolume(IntBounds3 bounds) {
        this(bounds, null);
    }

    public RestoredStructureVolume(SavedStructureVolume snapshot) {
        this(snapshot.bounds(), snapshot);
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final int index = index(x, y, z);
                    final BlockState state = snapshot.blockState(x, y, z);
                    if (state != null && !state.isAir()) {
                        states[index] = state;
                    }
                    if (!snapshot.isSolid(x, y, z)) {
                        continue;
                    }
                    solid[index] = true;
                    mass[index] = snapshot.blockMass(x, y, z);
                    materials[index] = snapshot.blockMaterial(x, y, z);
                }
            }
        }
    }

    private RestoredStructureVolume(IntBounds3 bounds, @Nullable SavedStructureVolume source) {
        this.bounds = bounds;
        this.source = source;
        final int cells = Math.toIntExact(bounds.volume());
        this.solid = new boolean[cells];
        this.mass = new double[cells];
        this.materials = new PhysicsMaterial[cells];
        this.states = new BlockState[cells];
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return bounds.contains(x, y, z) && solid[index(x, y, z)];
    }

    @Override
    public double blockMass(int x, int y, int z) {
        return mass[index(x, y, z)];
    }

    @Override
    public PhysicsMaterial blockMaterial(int x, int y, int z) {
        final PhysicsMaterial material = materials[index(x, y, z)];
        return material == null ? PhysicsMaterial.DEFAULT : material;
    }

    @Override
    public BlockState blockState(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        final int index = index(x, y, z);
        final BlockState state = states[index];
        if (state != null) {
            return state;
        }
        if (!solid[index]) {
            return Blocks.AIR.defaultBlockState();
        }
        final BlockState sourceState = source == null ? null : source.blockState(x, y, z);
        return sourceState == null ? Blocks.STONE.defaultBlockState() : sourceState;
    }

    public void setSolid(int x, int y, int z, boolean value) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Voxel outside bounds: " + x + "," + y + "," + z);
        }
        solid[index(x, y, z)] = value;
    }

    public void setBlockState(int x, int y, int z, BlockState state) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Voxel outside bounds: " + x + "," + y + "," + z);
        }
        states[index(x, y, z)] = state;
    }

    /**
     * Full edit used by the interaction layer. The volume is already editable;
     * this single entry point also keeps mass and material in sync for newly
     * placed cells.
     */
    public void setBlock(int x, int y, int z, boolean value, double blockMass,
                         PhysicsMaterial material, BlockState state) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Voxel outside bounds: " + x + "," + y + "," + z);
        }
        final int index = index(x, y, z);
        solid[index] = value;
        mass[index] = value ? Math.max(blockMass, 1.0e-9) : 0.0;
        materials[index] = material == null ? PhysicsMaterial.DEFAULT : material;
        states[index] = state;
    }

    private int index(int x, int y, int z) {
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }
}
