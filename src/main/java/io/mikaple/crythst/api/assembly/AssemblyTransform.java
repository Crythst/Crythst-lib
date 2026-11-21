package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.util.Validate;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Maps between structure-local voxel coordinates and world block positions.
 *
 * <p>Voxel coordinates are the raw integer cells stored by the structure
 * volume; a voxel is a cube of {@code blockScale} metres whose centre is
 * {@code (x + 0.5, y + 0.5, z + 0.5) * blockScale}. The pose maps that local
 * metre space to the world. This is the same convention as
 * {@link io.mikaple.crythst.physics.structure.PhysicsStructure#blockCenter(int,
 * int, int)}, so a structure assembled and later disassembled at an unchanged
 * pose puts its blocks back where they came from.</p>
 *
 * <p>The helper deliberately does not know about plots or sub-levels. Crythst
 * structures live in world space; there is no second block grid or plot
 * coordinate system to convert to.</p>
 */
public record AssemblyTransform(Transform3d pose, double blockScale) {
    public AssemblyTransform {
        Objects.requireNonNull(pose, "pose");
        blockScale = Validate.positive(blockScale, "blockScale");
    }

    public static AssemblyTransform of(Transform3d pose, double blockScale) {
        return new AssemblyTransform(pose, blockScale);
    }

    /**
     * Transform anchored at a world block corner with one metre per voxel.
     */
    public static AssemblyTransform at(BlockPos anchor) {
        Objects.requireNonNull(anchor, "anchor");
        return new AssemblyTransform(Transform3d.of(new Vector3d(
                anchor.getX(), anchor.getY(), anchor.getZ())), 1.0);
    }

    /**
     * World-space centre of a voxel.
     */
    public Vector3d voxelCenter(int x, int y, int z) {
        return pose.transformPoint(new Vector3d(
                (x + 0.5) * blockScale,
                (y + 0.5) * blockScale,
                (z + 0.5) * blockScale));
    }

    /**
     * World block containing the centre of a voxel.
     */
    public BlockPos voxelToWorldBlock(int x, int y, int z) {
        final Vector3d center = voxelCenter(x, y, z);
        return BlockPos.containing(center.x, center.y, center.z);
    }

    /**
     * Converts a world point to fractional, centre-based voxel coordinates.
     * A result of {@code (0.0, 0.0, 0.0)} is the centre of voxel zero.
     */
    public Vector3d worldToVoxel(Vector3dc worldPoint) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        return pose.inverseTransformPoint(worldPoint)
                .mul(1.0 / blockScale)
                .sub(0.5, 0.5, 0.5);
    }

    /**
     * World block whose centre falls inside the given world block.
     */
    public BlockPos worldBlockToVoxel(BlockPos worldBlock) {
        Objects.requireNonNull(worldBlock, "worldBlock");
        final Vector3d point = new Vector3d(
                worldBlock.getX() + 0.5,
                worldBlock.getY() + 0.5,
                worldBlock.getZ() + 0.5);
        final Vector3d voxel = worldToVoxel(point);
        return BlockPos.containing(voxel.x, voxel.y, voxel.z);
    }
}
