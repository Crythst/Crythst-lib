package io.mikaple.crythst.api.schematic;

import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Dense voxel volume decoded from a schematic.
 *
 * <p>Solidity, per-cell mass and material are kept separately from the block
 * state. That is necessary because Minecraft logic blocks such as redstone
 * wire, torches and buttons occupy a voxel but have no collision shape. They
 * remain in the state array so block logic, rendering and persistence can copy
 * them, while the physics collider ignores them.</p>
 *
 * <p>The volume is immutable after loading. {@link Schematic} owns the only
 * instance and never exposes a mutator.</p>
 */
public final class SchematicVolume implements StatefulVoxelVolume {
    private static final double DEFAULT_MASS = 1000.0;

    private final IntBounds3 bounds;
    private final boolean[] solid;
    private final double[] mass;
    private final PhysicsMaterial[] materials;
    private final BlockState[] states;
    private int solidCount;
    private int nonAirCount;

    SchematicVolume(IntBounds3 bounds) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        final int cells = Math.toIntExact(bounds.volume());
        this.solid = new boolean[cells];
        this.mass = new double[cells];
        this.materials = new PhysicsMaterial[cells];
        this.states = new BlockState[cells];
        java.util.Arrays.fill(this.materials, PhysicsMaterial.DEFAULT);
    }

    void setBlock(int x, int y, int z, BlockState state, boolean isSolid,
                  double blockMass, PhysicsMaterial material) {
        final int index = index(x, y, z);
        if (states[index] != null) {
            throw new IllegalStateException("Duplicate schematic voxel " + x + "," + y + "," + z);
        }
        states[index] = Objects.requireNonNull(state, "state");
        nonAirCount++;
        if (isSolid) {
            solid[index] = true;
            mass[index] = blockMass > 0.0 && Double.isFinite(blockMass)
                    ? blockMass : DEFAULT_MASS;
            materials[index] = material == null ? PhysicsMaterial.DEFAULT : material;
            solidCount++;
        }
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return solid[index(x, y, z)];
    }

    @Override
    public double blockMass(int x, int y, int z) {
        final int index = index(x, y, z);
        return solid[index] && mass[index] > 0.0 ? mass[index] : DEFAULT_MASS;
    }

    @Override
    public PhysicsMaterial blockMaterial(int x, int y, int z) {
        return materials[index(x, y, z)];
    }

    /**
     * Block state for a voxel, including non-solid logic blocks.
     *
     * @return the state, or {@code null} outside the bounds
     */
    @Override
    public @Nullable BlockState blockState(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return null;
        }
        return states[index(x, y, z)];
    }

    /** Number of cells that participate in collision. */
    public int solidCount() {
        return solidCount;
    }

    /** Number of cells with an explicit non-air block state. */
    public int nonAirCount() {
        return nonAirCount;
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
