package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Immutable description of one physics structure as sent to clients.
 *
 * <p>The volume is a sparse palette: {@code cells[i] == 0} means empty and
 * {@code cells[i] == n} refers to {@code palette[n - 1]}. Palette entries are
 * Minecraft block-state registry ids ({@link net.minecraft.world.level.block.Block#getId}),
 * so client and server must use the same block registry. See
 * {@code docs/multiplayer-sync.md} for the compatibility caveat.</p>
 *
 * @param bodyId       physics body id, unique within the level
 * @param pose         structure pose (not the centre-of-mass body pose)
 * @param bounds       structure-local inclusive-min/exclusive-max voxel bounds
 * @param palette      block-state registry ids; index 0 is implicit air
 * @param cells        one entry per voxel, 0 = empty, otherwise 1-based palette index
 * @param blockScale   metres per voxel
 * @param massScale    mass multiplier applied by the server
 * @param colliderMass unscaled collider mass in kilograms
 * @param centerOfMass centre of mass in voxel coordinates
 */
public record StructureSnapshot(
        long bodyId,
        Transform3d pose,
        IntBounds3 bounds,
        int[] palette,
        int[] cells,
        double blockScale,
        double massScale,
        double colliderMass,
        Vector3d centerOfMass
) {
    public StructureSnapshot {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        pose = Objects.requireNonNull(pose, "pose");
        bounds = Objects.requireNonNull(bounds, "bounds");
        palette = palette == null ? new int[0] : palette.clone();
        cells = cells == null ? new int[0] : cells.clone();
        centerOfMass = Objects.requireNonNull(centerOfMass, "centerOfMass");

        if (palette.length > StructureSyncLimits.MAX_PALETTE) {
            throw new IllegalArgumentException("Palette has " + palette.length
                    + " entries, limit is " + StructureSyncLimits.MAX_PALETTE);
        }
        if (bounds.volume() > StructureSyncLimits.MAX_CELLS) {
            throw new IllegalArgumentException("Structure has " + bounds.volume()
                    + " voxels, limit is " + StructureSyncLimits.MAX_CELLS);
        }
        if (cells.length != bounds.volume()) {
            throw new IllegalArgumentException("Cell count " + cells.length
                    + " does not match bounds volume " + bounds.volume());
        }
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive and finite: " + blockScale);
        }
        if (!(massScale > 0.0) || !Double.isFinite(massScale)) {
            throw new IllegalArgumentException("massScale must be positive and finite: " + massScale);
        }
        if (!(colliderMass >= 0.0) || !Double.isFinite(colliderMass)) {
            throw new IllegalArgumentException("colliderMass must be finite and non-negative: " + colliderMass);
        }
        if (!isFinite(centerOfMass)) {
            throw new IllegalArgumentException("centerOfMass must be finite: " + centerOfMass);
        }
        for (int cell : cells) {
            if (cell < 0 || cell > palette.length) {
                throw new IllegalArgumentException("Cell index " + cell
                        + " outside palette range 0.." + palette.length);
            }
        }
    }

    @Override
    public int[] palette() {
        return palette.clone();
    }

    @Override
    public int[] cells() {
        return cells.clone();
    }

    @Override
    public Vector3d centerOfMass() {
        return new Vector3d(centerOfMass);
    }

    public int cellCount() {
        return cells.length;
    }

    public int cellAt(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            throw new IndexOutOfBoundsException("Voxel " + x + "," + y + "," + z + " outside " + bounds);
        }
        final int index = ((y - bounds.minY()) * bounds.sizeZ() + (z - bounds.minZ()))
                * bounds.sizeX() + (x - bounds.minX());
        return cells[index];
    }

    public boolean isSolid(int x, int y, int z) {
        return cellAt(x, y, z) != 0;
    }

    /** Centre-of-mass offset in metres, relative to the structure pose. */
    public Vector3d centerOfMassOffset() {
        return new Vector3d(centerOfMass).mul(blockScale);
    }

    /** Actual body mass after the server's mass scale. */
    public double mass() {
        return colliderMass * massScale;
    }

    private static boolean isFinite(Vector3d value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
