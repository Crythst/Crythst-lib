package io.mikaple.crythst.physics.voxel;

import org.junit.jupiter.api.Test;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelColliderBuilderTest {
    @Test
    void fullCubeMergesIntoOneBoxWithCorrectMassAndInertia() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 2);
        final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, 1000.0,
                io.mikaple.crythst.physics.material.PhysicsMaterial.STONE);
        final VoxelColliderData data = VoxelColliderBuilder.build(volume);

        assertEquals(1, data.boxes().size());
        assertEquals(8000.0, data.mass(), 1.0e-9);
        assertEquals(1.0, data.centerOfMass().x, 1.0e-9);
        assertEquals(1.0, data.centerOfMass().y, 1.0e-9);
        assertEquals(1.0, data.centerOfMass().z, 1.0e-9);

        final double expected = 8000.0 / 12.0 * (4.0 + 4.0);
        assertEquals(expected, data.inertia().m00, 1.0e-6);
        assertEquals(expected, data.inertia().m11, 1.0e-6);
        assertEquals(expected, data.inertia().m22, 1.0e-6);
        assertEquals(0.0, data.inertia().m01, 1.0e-9);
    }

    @Test
    void lShapeProducesTwoBoxesAndWeightedCentreOfMass() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 1);
        final MutableVoxelVolume volume = new MutableVoxelVolume(bounds);
        volume.setBlock(0, 0, 0, true, 1000.0, io.mikaple.crythst.physics.material.PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 0, true, 1000.0, io.mikaple.crythst.physics.material.PhysicsMaterial.STONE);
        volume.setBlock(0, 1, 0, true, 1000.0, io.mikaple.crythst.physics.material.PhysicsMaterial.STONE);

        final VoxelColliderData data = VoxelColliderBuilder.build(volume);
        assertEquals(2, data.boxes().size());
        assertEquals(3000.0, data.mass(), 1.0e-9);
        assertEquals(5.0 / 6.0, data.centerOfMass().x, 1.0e-9);
        assertEquals(5.0 / 6.0, data.centerOfMass().y, 1.0e-9);
        assertTrue(data.inertia().determinant() > 0.0);
    }

    @Test
    void greedyMergeCanBeDisabled() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 2, 2);
        final MutableVoxelVolume volume = VoxelVolumes.filled(bounds, 1000.0,
                io.mikaple.crythst.physics.material.PhysicsMaterial.STONE);
        final VoxelColliderData data = VoxelColliderBuilder.build(volume,
                VoxelColliderOptions.DEFAULT.withGreedyMerge(false));
        assertEquals(8, data.boxes().size());
    }

    @Test
    void compoundShapeIsCentredOnCentreOfMass() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 1, 1);
        final MutableVoxelVolume volume = new MutableVoxelVolume(bounds);
        volume.setBlock(1, 0, 0, true, 1000.0, io.mikaple.crythst.physics.material.PhysicsMaterial.STONE);
        final VoxelColliderData data = VoxelColliderBuilder.build(volume);
        assertEquals(1.5, data.centerOfMass().x, 1.0e-9);
        final var shape = data.toCompoundShape();
        assertEquals(1, shape.children().size());
        assertEquals(0.0, shape.children().get(0).transform().position().x, 1.0e-9);
    }
}
