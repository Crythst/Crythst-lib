package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only, raycast-oriented view of one physics structure.
 *
 * <p>The pose maps structure-local metre coordinates to world space, exactly
 * like {@link PhysicsStructure#pose()}: the centre of the voxel {@code (x, y, z)}
 * is at {@code pose.transformPoint(((x + 0.5) * scale, ...))}. Voxel indices are
 * therefore divided by {@code blockScale} before the pose inverse is used, and
 * the integer grid used by the raycaster is the same grid as
 * {@link #bounds()}.</p>
 *
 * <p>Implementations exist for server structures, the integrated-client render
 * snapshots, and the multiplayer block-state snapshots. Keeping the raycast on
 * this abstraction makes the authoritative server pass and the client
 * prediction pass share one algorithm.</p>
 */
public interface StructureVoxelView {
    /** Physics body id, or a stable snapshot id on the client. */
    long bodyId();

    /** Structure pose in world space (not the centre-of-mass body pose). */
    Transform3d pose();

    /** Metres per voxel. */
    double blockScale();

    /** Integer voxel bounds of this view. */
    IntBounds3 bounds();

    boolean isSolid(int x, int y, int z);

    /** Block state shown for a solid voxel; never {@code null}. */
    BlockState blockState(int x, int y, int z);

    /**
     * The backing server structure, or {@code null} for a network snapshot.
     * Authoritative edits require a non-null value.
     */
    default @Nullable PhysicsStructure structure() {
        return null;
    }

    /** Whether the underlying body has been removed. */
    default boolean closed() {
        return false;
    }
}
