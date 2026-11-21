package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssemblyTransformTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void identityTransformMapsVoxelsToAdjacentWorldBlocks() {
        final AssemblyTransform transform = AssemblyTransform.at(new BlockPos(10, 64, -5));

        assertEquals(new BlockPos(10, 64, -5), transform.voxelToWorldBlock(0, 0, 0));
        assertEquals(new BlockPos(12, 65, -2), transform.voxelToWorldBlock(2, 1, 3));
        assertEquals(new BlockPos(2, 1, 3),
                transform.worldBlockToVoxel(new BlockPos(12, 65, -2)));
    }

    @Test
    void blockScaleLessThanOnePacksSeveralVoxelsIntoOneBlock() {
        final AssemblyTransform transform = AssemblyTransform.of(
                Transform3d.of(new Vector3d()), 0.5);

        assertEquals(new BlockPos(0, 0, 0), transform.voxelToWorldBlock(0, 0, 0));
        assertEquals(new BlockPos(0, 0, 0), transform.voxelToWorldBlock(1, 0, 0));
        assertEquals(new BlockPos(1, 0, 0), transform.voxelToWorldBlock(2, 0, 0));
        final Vector3d center = transform.voxelCenter(2, 0, 0);
        assertEquals(1.25, center.x, EPSILON);
        assertEquals(0.25, center.y, EPSILON);
        assertEquals(0.25, center.z, EPSILON);
    }

    @Test
    void rotatedPoseMapsTheVoxelCentreNotJustItsCorner() {
        final AssemblyTransform transform = AssemblyTransform.of(
                Transform3d.of(new Vector3d(),
                        new Quaterniond().rotateY(Math.PI / 2.0)), 1.0);

        final Vector3d center = transform.voxelCenter(1, 0, 0);
        assertEquals(0.5, center.x, EPSILON);
        assertEquals(0.5, center.y, EPSILON);
        assertEquals(-1.5, center.z, EPSILON);
        assertEquals(new BlockPos(0, 0, -2), transform.voxelToWorldBlock(1, 0, 0));
    }
}
