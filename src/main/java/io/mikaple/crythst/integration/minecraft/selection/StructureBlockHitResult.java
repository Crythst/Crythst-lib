package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * A normal vanilla {@link BlockHitResult} that also describes the exact
 * structure voxel that was hit.
 *
 * <p>Vanilla consumers only look at the inherited fields: {@link #getLocation()}
 * is the world-space entry point, {@link #getDirection()} is the outward world
 * face, {@link #getBlockPos()} is the real block containing the hit point and
 * {@link #getType()} is {@code BLOCK}. The extra accessors let the outline and
 * interaction code use the structure-local voxel, the structure pose and the
 * block state without running a second, differently-quantised raycast.</p>
 *
 * <p>The hit is immutable. Nested records or values are copied where they are
 * mutable.</p>
 */
public class StructureBlockHitResult extends BlockHitResult {
    private final long bodyId;
    private final Transform3d structurePose;
    private final int voxelX;
    private final int voxelY;
    private final int voxelZ;
    private final Direction localFace;
    private final double blockScale;
    private final BlockState blockState;
    private final double distance;
    private final @Nullable PhysicsStructure structure;

    public StructureBlockHitResult(Vec3 location, Direction worldFace, BlockPos blockPos,
                                   long bodyId, Transform3d structurePose,
                                   int voxelX, int voxelY, int voxelZ, Direction localFace,
                                   double blockScale, BlockState blockState, double distance,
                                   @Nullable PhysicsStructure structure) {
        super(location, worldFace, blockPos, false);
        this.bodyId = bodyId;
        this.structurePose = structurePose;
        this.voxelX = voxelX;
        this.voxelY = voxelY;
        this.voxelZ = voxelZ;
        this.localFace = localFace;
        this.blockScale = blockScale;
        this.blockState = blockState;
        this.distance = distance;
        this.structure = structure;
    }

    /**
     * @return the structure hit described by {@code result}, or {@code null}
     *         when it is an ordinary vanilla hit
     */
    public static @Nullable StructureBlockHitResult of(@Nullable HitResult result) {
        return result instanceof StructureBlockHitResult structureHit ? structureHit : null;
    }

    public long bodyId() {
        return bodyId;
    }

    /** Structure pose used for this hit (not the centre-of-mass body pose). */
    public Transform3d structurePose() {
        return structurePose;
    }

    public int voxelX() {
        return voxelX;
    }

    public int voxelY() {
        return voxelY;
    }

    public int voxelZ() {
        return voxelZ;
    }

    /** Structure-local outward face of the hit voxel. */
    public Direction localFace() {
        return localFace;
    }

    /** World-space outward face; identical to {@link #getDirection()}. */
    public Direction worldFace() {
        return getDirection();
    }

    public double blockScale() {
        return blockScale;
    }

    /** State of the hit voxel as seen by the raycast. */
    public BlockState blockState() {
        return blockState;
    }

    /** World-space distance from the ray origin to the hit. */
    public double distance() {
        return distance;
    }

    /** Authoritative server structure, or {@code null} for a client snapshot. */
    public @Nullable PhysicsStructure structure() {
        return structure;
    }

    /** Convenience copy of {@link #getLocation()} in JOML form. */
    public Vector3d worldHit() {
        final Vec3 location = getLocation();
        return new Vector3d(location.x, location.y, location.z);
    }

    /** Voxel adjacent to the clicked face, in structure-local coordinates. */
    public int adjacentX() {
        return voxelX + localFace.getStepX();
    }

    public int adjacentY() {
        return voxelY + localFace.getStepY();
    }

    public int adjacentZ() {
        return voxelZ + localFace.getStepZ();
    }

    /**
     * The same hit as a vanilla click. Kept as a named operation for callers
     * that used the old synthetic-hit API; no geometry is rebuilt.
     */
    public BlockHitResult clickResult() {
        return this;
    }
}
