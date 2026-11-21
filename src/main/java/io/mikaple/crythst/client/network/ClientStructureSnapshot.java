package io.mikaple.crythst.client.network;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable, client-thread-safe view of one synchronised structure.
 *
 * <p>Block-state ids are resolved once when the snapshot is applied, so a
 * renderer can call {@link #blockStateAt(int, int, int)} without touching the
 * network payload or any server state. Accessors defensively copy arrays, but
 * internal pose updates share the (never mutated) volume arrays.</p>
 */
public final class ClientStructureSnapshot {
    private static final AtomicLong NEXT_GEOMETRY_REVISION = new AtomicLong();

    private final long bodyId;
    private final Transform3d pose;
    private final IntBounds3 bounds;
    private final BlockState[] palette;
    private final int[] cells;
    private final double blockScale;
    private final double massScale;
    private final double colliderMass;
    private final Vector3d centerOfMass;
    private final int nonAirBlocks;
    private final long geometryRevision;

    private ClientStructureSnapshot(long bodyId, Transform3d pose, IntBounds3 bounds,
                                    BlockState[] palette, int[] cells,
                                    double blockScale, double massScale, double colliderMass,
                                    Vector3d centerOfMass, int nonAirBlocks, long geometryRevision,
                                    boolean copyArrays) {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        this.bodyId = bodyId;
        this.pose = Objects.requireNonNull(pose, "pose");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.palette = copyArrays ? palette.clone() : palette;
        this.cells = copyArrays ? cells.clone() : cells;
        this.blockScale = blockScale;
        this.massScale = massScale;
        this.colliderMass = colliderMass;
        this.centerOfMass = new Vector3d(centerOfMass);
        this.nonAirBlocks = nonAirBlocks;
        this.geometryRevision = geometryRevision;
        if (this.cells.length != this.bounds.volume()) {
            throw new IllegalArgumentException("Cell count " + this.cells.length
                    + " does not match bounds volume " + this.bounds.volume());
        }
    }

    static ClientStructureSnapshot fromWire(StructureSnapshot wire) {
        final int[] wirePalette = wire.palette();
        final int[] wireCells = wire.cells();
        final BlockState[] states = new BlockState[wirePalette.length];
        final BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < wirePalette.length; i++) {
            BlockState state = null;
            try {
                state = Block.stateById(wirePalette[i]);
            } catch (RuntimeException ignored) {
                // Unknown id: show air instead of crashing the render thread.
            }
            states[i] = state == null ? air : state;
        }
        int nonAir = 0;
        for (int cell : wireCells) {
            if (cell != 0) {
                nonAir++;
            }
        }
        // wire.palette()/cells() already returned fresh copies.
        return new ClientStructureSnapshot(
                wire.bodyId(), wire.pose(), wire.bounds(), states, wireCells,
                wire.blockScale(), wire.massScale(), wire.colliderMass(), wire.centerOfMass(),
                nonAir, NEXT_GEOMETRY_REVISION.incrementAndGet(), false);
    }

    public long bodyId() {
        return bodyId;
    }

    public Transform3d pose() {
        return pose;
    }

    public IntBounds3 bounds() {
        return bounds;
    }

    public BlockState[] palette() {
        return palette.clone();
    }

    public int[] cells() {
        return cells.clone();
    }

    public double blockScale() {
        return blockScale;
    }

    public double massScale() {
        return massScale;
    }

    public double colliderMass() {
        return colliderMass;
    }

    public Vector3d centerOfMass() {
        return new Vector3d(centerOfMass);
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

    public BlockState blockStateAt(int x, int y, int z) {
        final int cell = cellAt(x, y, z);
        if (cell == 0 || cell > palette.length) {
            return Blocks.AIR.defaultBlockState();
        }
        return palette[cell - 1];
    }

    /** Centre-of-mass offset in metres, relative to the structure pose. */
    public Vector3d centerOfMassOffset() {
        return new Vector3d(centerOfMass).mul(blockScale);
    }

    public double mass() {
        return colliderMass * massScale;
    }

    /** Number of non-empty cells in this snapshot. */
    public int nonAirBlocks() {
        return nonAirBlocks;
    }

    /**
     * Changes only when the block geometry changes. Pose-only updates
     * ({@link #withPose}) preserve it, letting renderers reuse cached meshes.
     */
    public long geometryRevision() {
        return geometryRevision;
    }

    public ClientStructureSnapshot withPose(Transform3d newPose) {
        return new ClientStructureSnapshot(bodyId, newPose, bounds, palette, cells,
                blockScale, massScale, colliderMass, centerOfMass,
                nonAirBlocks, geometryRevision, false);
    }
}
