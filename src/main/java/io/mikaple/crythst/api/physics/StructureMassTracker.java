package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Per-body and per-cell mass lookups for one structure.
 *
 * <p>The total mass and centre of mass come from the collider, so custom
 * collision-shape, dynamic-collider and physics-property hooks are reflected
 * exactly. Per-cell masses come from the structure volume and are expressed in
 * the volume's local voxel grid. For a volume with custom fractional colliders
 * the sum of the per-cell values can differ from {@link #mass()} because a
 * single voxel may contribute a different shape volume; use the total for
 * physics and the per-cell values for diagnostics and gameplay rules.</p>
 *
 * <p><strong>Units:</strong> kilograms, kilograms per voxel cell, metres and
 * {@code kg·m²}. <strong>Threading:</strong> server thread only; the tracker
 * reads the live structure and volume. The object is a lightweight view, not a
 * snapshot; reacquire it after a reindex.</p>
 */
public final class StructureMassTracker {
    private final PhysicsStructure structure;

    private StructureMassTracker(PhysicsStructure structure) {
        this.structure = structure;
    }

    /**
     * Creates a tracker for a structure.
     *
     * @param structure live structure; must not be {@code null}
     */
    public static StructureMassTracker of(PhysicsStructure structure) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        return new StructureMassTracker(structure);
    }

    /** Wrapped structure. */
    public PhysicsStructure structure() {
        return structure;
    }

    /** Physics body id. */
    public long bodyId() {
        return structure.body().id();
    }

    /**
     * Total collider mass in kilograms, scaled by the structure's mass scale.
     * This is the mass used by the rigid body for dynamic structures.
     */
    public double mass() {
        return structure.collider().mass() * structure.massScale();
    }

    /**
     * Mass before applying {@link PhysicsStructure#massScale()}.
     */
    public double colliderMass() {
        return structure.collider().mass();
    }

    /** Structure mass scale (dimensionless, positive). */
    public double massScale() {
        return structure.massScale();
    }

    /** Centre of mass in structure-local metres. */
    public Vector3d centerOfMass() {
        return new Vector3d(structure.collider().centerOfMass())
                .mul(structure.blockScale());
    }

    /** Centre of mass in structure-local metres, copied into {@code dest}. */
    public Vector3d centerOfMass(Vector3d dest) {
        return centerOfMass().get(dest);
    }

    /** World-space centre of mass in metres. */
    public Vector3d centerOfMassWorld() {
        return structure.pose().transformPoint(centerOfMass());
    }

    /** World-space centre of mass in metres, copied into {@code dest}. */
    public Vector3d centerOfMassWorld(Vector3d dest) {
        return structure.pose().transformPoint(centerOfMass(), dest);
    }

    /** Local inertia tensor in {@code kg·m²}, scaled by the mass scale. */
    public org.joml.Matrix3d inertia() {
        return MassData.forStructure(structure).inertia();
    }

    /** Integer bounds of the structure volume. */
    public IntBounds3 bounds() {
        return structure.volume().bounds();
    }

    /** Number of solid cells in the volume. */
    public int solidCellCount() {
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        int count = 0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (volume.isSolid(x, y, z)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Whether a local voxel cell is solid. Out-of-bounds positions are empty. */
    public boolean isSolid(int x, int y, int z) {
        final VoxelVolume volume = structure.volume();
        return volume.bounds().contains(x, y, z) && volume.isSolid(x, y, z);
    }

    /**
     * Mass of one local voxel cell in kilograms, or {@code 0} when the cell is
     * empty or outside the volume.
     */
    public double cellMass(int x, int y, int z) {
        final VoxelVolume volume = structure.volume();
        if (!volume.bounds().contains(x, y, z) || !volume.isSolid(x, y, z)) {
            return 0.0;
        }
        return volume.blockMass(x, y, z);
    }

    /**
     * Mass of the cell containing a structure-local point, in local metres.
     * Returns {@code 0} outside the volume.
     */
    public double massAtLocal(Vector3dc localMetres) {
        if (localMetres == null) {
            return 0.0;
        }
        final double scale = structure.blockScale();
        final int x = (int) Math.floor(localMetres.x() / scale);
        final int y = (int) Math.floor(localMetres.y() / scale);
        final int z = (int) Math.floor(localMetres.z() / scale);
        return cellMass(x, y, z);
    }

    /**
     * Mass of the cell containing a world-space point, in kilograms, or
     * {@code 0} outside the solid volume.
     */
    public double massAtWorld(Vector3dc worldPoint) {
        if (worldPoint == null) {
            return 0.0;
        }
        final Vector3d voxel = structure.worldToVoxel(new Vector3d(), worldPoint);
        return cellMass(
                (int) Math.floor(voxel.x + 0.5),
                (int) Math.floor(voxel.y + 0.5),
                (int) Math.floor(voxel.z + 0.5));
    }

    /**
     * World-space centre of one local voxel cell.
     *
     * @return the centre, or {@code null} outside the volume
     */
    public @Nullable Vector3d cellCenterWorld(int x, int y, int z, Vector3d dest) {
        if (!structure.volume().bounds().contains(x, y, z)) {
            return null;
        }
        return structure.blockCenter(dest, x, y, z);
    }

    /**
     * Sum of the per-cell masses. This is a volume-level diagnostic; use
     * {@link #mass()} for the body mass that includes collider hooks.
     */
    public double summedCellMass() {
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        double mass = 0.0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (volume.isSolid(x, y, z)) {
                        mass += volume.blockMass(x, y, z);
                    }
                }
            }
        }
        return mass;
    }

    /** Collider data backing the total mass and inertia. */
    public VoxelColliderData collider() {
        return structure.collider();
    }

    /** Pose used to project per-cell data into world space. */
    public Transform3d pose() {
        return structure.pose();
    }
}
