package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.util.Validate;

import java.util.Arrays;

/**
 * Dense mutable voxel grid, handy for tests and for player-built structures that
 * are not backed by a Minecraft {@code Level}.
 */
public final class MutableVoxelVolume implements VoxelVolume {
    private final IntBounds3 bounds;
    private final boolean[] solid;
    private final double[] mass;
    private final PhysicsMaterial[] materials;

    public MutableVoxelVolume(IntBounds3 bounds) {
        this.bounds = Validate.notNull(bounds, "bounds");
        final int cells = Math.toIntExact(bounds.volume());
        this.solid = new boolean[cells];
        this.mass = new double[cells];
        this.materials = new PhysicsMaterial[cells];
        Arrays.fill(this.materials, PhysicsMaterial.DEFAULT);
    }

    public void setBlock(int x, int y, int z, boolean isSolid, double blockMass, PhysicsMaterial material) {
        final int index = index(x, y, z);
        solid[index] = isSolid;
        mass[index] = blockMass;
        materials[index] = material == null ? PhysicsMaterial.DEFAULT : material;
    }

    public void setSolid(int x, int y, int z, boolean isSolid) {
        final int index = index(x, y, z);
        solid[index] = isSolid;
        if (isSolid && mass[index] <= 0.0) {
            mass[index] = 1000.0;
        }
    }

    public void clear() {
        Arrays.fill(solid, false);
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
        final double value = mass[index(x, y, z)];
        return value > 0.0 ? value : 1000.0;
    }

    @Override
    public PhysicsMaterial blockMaterial(int x, int y, int z) {
        return materials[index(x, y, z)];
    }

    private int index(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Voxel " + x + "," + y + "," + z + " outside " + bounds);
        }
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }
}
