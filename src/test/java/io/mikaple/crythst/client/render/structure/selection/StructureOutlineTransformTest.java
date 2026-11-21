package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure pose math for the structure outline. No client or level is needed: the
 * matrix must map block-local shape coordinates onto the same camera-relative
 * world points as the structure renderer's pose.
 */
class StructureOutlineTransformTest {
    private static final double EPSILON = 1.0e-4;

    @Test
    void identityPoseMapsVoxelCornerRelativeToCamera() {
        final Transform3d pose = Transform3d.of(new Vector3d(0.0, 0.0, 0.0));
        final Vector3d camera = new Vector3d(10.0, 64.0, -3.0);

        final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                pose, 2, 1, -1, 1.0, camera);

        assertVector(new Vector3d(2.0, 1.0, -1.0).sub(camera),
                transform(matrix, new Vector3d()));
        assertVector(new Vector3d(3.0, 2.0, 0.0).sub(camera),
                transform(matrix, new Vector3d(1.0, 1.0, 1.0)));
    }

    @Test
    void rotationAndScaleMatchStructurePoseForEveryCorner() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(12.25, -4.5, 103.75),
                new Quaterniond()
                        .rotateY(Math.toRadians(37.0))
                        .rotateX(Math.toRadians(-19.0))
                        .rotateZ(Math.toRadians(71.0)));
        final Vector3d camera = new Vector3d(9.0, -2.0, 100.0);
        final double scale = 0.375;
        final int voxelX = -3;
        final int voxelY = 2;
        final int voxelZ = 7;

        final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                pose, voxelX, voxelY, voxelZ, scale, camera);

        for (int i = 0; i < 8; i++) {
            final Vector3d local = new Vector3d(
                    (i & 1) == 0 ? 0.0 : 1.0,
                    (i & 2) == 0 ? 0.0 : 1.0,
                    (i & 4) == 0 ? 0.0 : 1.0
            );
            final Vector3d structureLocal = new Vector3d(
                    (voxelX + local.x) * scale,
                    (voxelY + local.y) * scale,
                    (voxelZ + local.z) * scale
            );
            final Vector3d expected = pose.transformPoint(structureLocal).sub(camera);
            assertVector(expected, transform(matrix, new Vector3d(local)));
        }
    }

    @Test
    void scaleChangesOutlineSizeButNotPosition() {
        final Transform3d pose = Transform3d.of(new Vector3d(1.0, 2.0, 3.0));
        final Vector3d camera = new Vector3d(0.0, 0.0, 0.0);

        final Matrix4f unit = StructureOutlineTransform.cameraRelativeMatrix(
                pose, 4, 0, 0, 1.0, camera);
        final Matrix4f half = StructureOutlineTransform.cameraRelativeMatrix(
                pose, 4, 0, 0, 0.5, camera);

        final Vector3d unitOrigin = transform(unit, new Vector3d());
        final Vector3d halfOrigin = transform(half, new Vector3d());
        final Vector3d unitCorner = transform(unit, new Vector3d(1.0, 1.0, 1.0));
        final Vector3d halfCorner = transform(half, new Vector3d(1.0, 1.0, 1.0));
        // The half-scale voxel origin is at 4 * 0.5 = 2 metres, and its far
        // corner is another 0.5 metres in each axis.
        assertEquals(Math.sqrt(3.0) * 0.5, halfCorner.distance(halfOrigin), EPSILON);
        assertEquals(Math.sqrt(3.0), unitCorner.distance(unitOrigin), EPSILON);
        assertEquals(4.0, unitOrigin.distance(new Vector3d(1.0, 2.0, 3.0)), EPSILON);
        assertEquals(2.0, halfOrigin.distance(new Vector3d(1.0, 2.0, 3.0)), EPSILON);
    }

    private static Vector3d transform(Matrix4f matrix, Vector3d point) {
        final Vector3f result = matrix.transformPosition(
                new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vector3d(result);
    }

    private static void assertVector(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
