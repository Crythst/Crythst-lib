package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Optional per-voxel overrides consumed by {@link VoxelColliderBuilder}.
 *
 * <p>This is the backend-neutral bridge between Minecraft-facing block hooks
 * and the core voxel collider. All methods are called on the thread that builds
 * the structure (normally the server thread) and must not mutate native physics
 * state. Returning {@code null} means "use the volume's value".</p>
 *
 * <p>When a cell returns custom {@link #collisionParts collision parts} or a
 * custom {@link #centerOfMass center of mass}, the builder deliberately does not
 * greedy-merge that cell with its neighbours. This keeps the override exact at
 * the cost of one or a few extra boxes for the affected blocks.</p>
 */
public interface VoxelColliderHooks {
    /** Hook set that never overrides anything. */
    VoxelColliderHooks NONE = new VoxelColliderHooks() {
    };

    /**
     * Collision geometry for one solid cell.
     *
     * @return parts in structure-local voxel coordinates, an empty list to keep
     *         the cell's mass but remove its collision geometry, or {@code null}
     *         for the normal one-voxel cube
     */
    default List<VoxelCellPart> collisionParts(int x, int y, int z, VoxelVolume volume) {
        return null;
    }

    /**
     * Custom centre of mass of one cell, relative to the cell's lower corner.
     * Values are normally in {@code [0, 1]} on each axis.
     *
     * @return an offset in voxel units, or {@code null} for the geometric centre
     */
    default Vector3dc centerOfMass(int x, int y, int z, VoxelVolume volume) {
        return null;
    }

    /**
     * Per-cell mass override in kilograms.
     *
     * @return a positive mass, or {@code null} to use {@link VoxelVolume#blockMass}
     */
    default Double mass(int x, int y, int z, VoxelVolume volume) {
        return null;
    }

    /**
     * Per-cell surface material override.
     *
     * @return a material, or {@code null} to use {@link VoxelVolume#blockMaterial}
     */
    default PhysicsMaterial material(int x, int y, int z, VoxelVolume volume) {
        return null;
    }
}
