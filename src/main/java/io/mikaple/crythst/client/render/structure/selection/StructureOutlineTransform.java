package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Camera-relative pose for one structure voxel.
 *
 * <p>Vanilla submits a block outline with a pose stack translated by
 * {@code blockPos - cameraPos}, then submits a block-local shape in
 * {@code [0, 1]^3}. For a structure voxel the grid translation is replaced by
 * the structure's real pose: the voxel's minimum corner is transformed into
 * world space, made camera-relative, rotated by the structure quaternion and
 * scaled by {@code blockScale}. The block-local shape is then submitted
 * unchanged, so stairs/slabs and all other shape boxes keep their exact local
 * geometry while the pose supplies orientation and size.</p>
 *
 * <p>The translation is computed in double precision; only the small
 * camera-relative offset reaches the float matrix. This is the same convention
 * used by the structure block renderer.</p>
 */
public final class StructureOutlineTransform {
    private StructureOutlineTransform() {
    }

    /**
     * Builds a matrix mapping block-local coordinates in {@code [0, 1]^3} to
     * camera-relative world space for one structure voxel.
     *
     * @param pose       structure-origin pose
     * @param voxelX     structure-local voxel coordinate
     * @param voxelY     structure-local voxel coordinate
     * @param voxelZ     structure-local voxel coordinate
     * @param blockScale metres per voxel
     * @param cameraPos  camera position in world space
     * @return {@code translate(voxelWorld - camera) * rotate(pose.rotation) * scale(blockScale)}
     */
    public static Matrix4f cameraRelativeMatrix(Transform3d pose, int voxelX, int voxelY, int voxelZ,
                                                double blockScale, Vector3dc cameraPos) {
        final Vector3d localCorner = new Vector3d(
                voxelX * blockScale,
                voxelY * blockScale,
                voxelZ * blockScale
        );
        final Vector3d worldCorner = pose.transformPoint(localCorner);
        final Quaterniond rotation = pose.rotation();
        final Quaternionf quaternion = new Quaternionf(
                (float) rotation.x,
                (float) rotation.y,
                (float) rotation.z,
                (float) rotation.w
        ).normalize();
        final float scale = (float) blockScale;
        return new Matrix4f()
                .translation(
                        (float) (worldCorner.x - cameraPos.x()),
                        (float) (worldCorner.y - cameraPos.y()),
                        (float) (worldCorner.z - cameraPos.z())
                )
                .rotate(quaternion)
                .scale(scale, scale, scale);
    }
}
