package io.mikaple.crythst.client.render.structure;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the structure-pose / body-pose / centre-of-mass
 * convention used by every structure renderer.
 *
 * <p>Collision uses {@code bodyPose * (voxel*scale - COM)}. The renderers use
 * {@code structurePose * (voxel*scale)}. The two must produce the same world
 * point. Mixing structure pose with COM-subtracted locals shifts rendering by
 * {@code -R*COM}.</p>
 */
class StructureAlignmentTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void structurePoseAndUnshiftedVoxelsMatchCollisionShape() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 1, 1);
        final VoxelVolumeForTest volume = new VoxelVolumeForTest(bounds);
        final double scale = 1.25;
        final VoxelColliderData collider = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT.withBlockScale(scale));
        final Vector3d centerOfMass = collider.centerOfMass().mul(scale);
        final Transform3d structurePose = new Transform3d(
                new Vector3d(10.0, 20.0, 30.0),
                new Quaterniond().rotateY(Math.PI / 3.0));
        final StructureRenderState state = new StructureRenderState(
                1L, structurePose, collider, centerOfMass, scale);

        // The rigid body is placed at structurePose + R*COM.
        final Transform3d bodyPose = state.bodyPose();
        final Vector3d bodyFromStructure = new Vector3d(centerOfMass)
                .rotate(structurePose.rotation());
        assertEquals(structurePose.position().x + bodyFromStructure.x, bodyPose.position().x, EPSILON);
        assertEquals(structurePose.position().y + bodyFromStructure.y, bodyPose.position().y, EPSILON);
        assertEquals(structurePose.position().z + bodyFromStructure.z, bodyPose.position().z, EPSILON);

        // A non-central voxel centre exercises an asymmetric COM.
        final Vector3d voxel = new Vector3d(0.5 * scale, 0.5 * scale, 0.5 * scale);
        final Vector3d collisionWorld = bodyPose.transformPoint(new Vector3d(voxel).sub(centerOfMass));
        final Vector3d renderWorld = structurePose.transformPoint(new Vector3d(voxel));
        assertEquals(0.0, collisionWorld.distance(renderWorld), EPSILON,
                "render must use structure pose with unshifted voxel coordinates");

        // The old mixed convention must be measurably wrong, otherwise this
        // test would not catch a regression.
        final Vector3d mixedWorld = structurePose.transformPoint(new Vector3d(voxel).sub(centerOfMass));
        assertTrue(collisionWorld.distance(mixedWorld) > 0.5,
                "mixing structure pose with COM-shifted locals should be visibly offset");
    }

    @Test
    void renderStateBoundsUseTheBodyPose() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 1, 1);
        final VoxelVolumeForTest volume = new VoxelVolumeForTest(bounds);
        final double scale = 1.25;
        final VoxelColliderData collider = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT.withBlockScale(scale));
        final Vector3d centerOfMass = collider.centerOfMass().mul(scale);
        final Transform3d structurePose = new Transform3d(
                new Vector3d(-4.0, 7.5, 2.0),
                new Quaterniond().rotateZ(Math.PI / 5.0));
        final StructureRenderState state = new StructureRenderState(
                2L, structurePose, collider, centerOfMass, scale);

        final Aabb expected = new Aabb(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(3.0 * scale, scale, scale)).transform(structurePose);
        final Aabb actual = state.aabb();
        assertEquals(expected.min().x, actual.min().x, EPSILON);
        assertEquals(expected.min().y, actual.min().y, EPSILON);
        assertEquals(expected.min().z, actual.min().z, EPSILON);
        assertEquals(expected.max().x, actual.max().x, EPSILON);
        assertEquals(expected.max().y, actual.max().y, EPSILON);
        assertEquals(expected.max().z, actual.max().z, EPSILON);
    }

    /**
     * Simple filled volume without pulling in a Minecraft world.
     */
    private static final class VoxelVolumeForTest implements io.mikaple.crythst.physics.voxel.VoxelVolume {
        private final IntBounds3 bounds;

        private VoxelVolumeForTest(IntBounds3 bounds) {
            this.bounds = bounds;
        }

        @Override
        public IntBounds3 bounds() {
            return bounds;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return bounds.contains(x, y, z);
        }

        @Override
        public double blockMass(int x, int y, int z) {
            return 1_000.0;
        }

        @Override
        public PhysicsMaterial blockMaterial(int x, int y, int z) {
            return PhysicsMaterial.STONE;
        }
    }
}
