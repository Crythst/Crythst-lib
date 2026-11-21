package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.physics.material.PhysicsMaterial;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelColliderHooksTest {
    private static final double EPSILON = 1.0e-9;

    private static MutableVoxelVolume cube() {
        final MutableVoxelVolume volume = new MutableVoxelVolume(new IntBounds3(0, 0, 0, 1, 1, 1));
        volume.setBlock(0, 0, 0, true, 1000.0, PhysicsMaterial.STONE);
        return volume;
    }

    @Test
    void customCenterOfMassShiftsMassButKeepsFullCubeCollision() {
        final MutableVoxelVolume volume = cube();
        final VoxelColliderHooks hooks = new VoxelColliderHooks() {
            @Override
            public Vector3d centerOfMass(int x, int y, int z, VoxelVolume ignored) {
                return new Vector3d(0.25, 0.5, 0.5);
            }
        };

        final VoxelColliderData data = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT, hooks);

        assertEquals(1000.0, data.mass(), EPSILON);
        assertEquals(0.25, data.centerOfMass().x, EPSILON);
        assertEquals(1, data.boxes().size());
        assertTrue(data.customParts().isEmpty());
        assertEquals(1, data.toCompoundShape().children().size());
    }

    @Test
    void customPartsReplaceCubeGeometryAndDriveInertia() {
        final MutableVoxelVolume volume = cube();
        final VoxelColliderHooks hooks = new VoxelColliderHooks() {
            @Override
            public List<VoxelCellPart> collisionParts(int x, int y, int z, VoxelVolume ignored) {
                return List.of(new VoxelCellPart(0.0, 0.0, 0.0, 1.0, 0.5, 1.0));
            }
        };

        final VoxelColliderData data = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT, hooks);

        assertEquals(1000.0, data.mass(), EPSILON);
        assertEquals(0.25, data.centerOfMass().y, EPSILON);
        assertEquals(0.5, data.size().y, EPSILON);
        assertTrue(data.boxes().isEmpty());
        assertEquals(1, data.customParts().size());
        assertEquals(1, data.toCompoundShape().children().size());
        assertTrue(data.inertia().m00 > 0.0);
    }

    @Test
    void massAndMaterialOverridesAreUsedByGreedyMerge() {
        final MutableVoxelVolume volume = cube();
        final VoxelColliderHooks hooks = new VoxelColliderHooks() {
            @Override
            public Double mass(int x, int y, int z, VoxelVolume ignored) {
                return 250.0;
            }

            @Override
            public PhysicsMaterial material(int x, int y, int z, VoxelVolume ignored) {
                return PhysicsMaterial.ICE;
            }
        };

        final VoxelColliderData data = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT, hooks);

        assertEquals(250.0, data.mass(), EPSILON);
        assertEquals(1, data.boxes().size());
        assertEquals(250.0, data.boxes().get(0).blockMass(), EPSILON);
        assertEquals(PhysicsMaterial.ICE, data.boxes().get(0).material());
    }

    @Test
    void emptyPartListKeepsMassAndRemovesGeometry() {
        final MutableVoxelVolume volume = cube();
        final VoxelColliderHooks hooks = new VoxelColliderHooks() {
            @Override
            public List<VoxelCellPart> collisionParts(int x, int y, int z, VoxelVolume ignored) {
                return List.of();
            }
        };

        final VoxelColliderData data = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT, hooks);

        assertEquals(1000.0, data.mass(), EPSILON);
        assertTrue(data.boxes().isEmpty());
        assertTrue(data.customParts().isEmpty());
    }
}
