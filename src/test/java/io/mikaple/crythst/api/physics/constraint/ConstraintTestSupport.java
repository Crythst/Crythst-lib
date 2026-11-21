package io.mikaple.crythst.api.physics.constraint;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConstraintTestSupport {
    private ConstraintTestSupport() {
    }

    static PhysicsStructure createStructure(PhysicsWorld world, Transform3d pose) {
        final MutableVoxelVolume volume = VoxelVolumes.filled(
                new IntBounds3(0, 0, 0, 3, 1, 2), 1000.0, PhysicsMaterial.STONE);
        return new PhysicsStructure(world, StructureDef.builder(volume).pose(pose).build());
    }

    static Vector3d centerOfMassOffset(PhysicsStructure structure) {
        return new Vector3d(structure.collider().centerOfMass())
                .mul(structure.blockScale());
    }

    static void assertVectorEquals(Vector3dc expected, Vector3dc actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance, "x");
        assertEquals(expected.y(), actual.y(), tolerance, "y");
        assertEquals(expected.z(), actual.z(), tolerance, "z");
    }

    static void assertTransformEquals(Transform3d expected, Transform3d actual, double tolerance) {
        assertVectorEquals(expected.position(), actual.position(), tolerance);
        assertQuaternionEquals(expected.rotation(), actual.rotation(), tolerance);
    }

    static void assertQuaternionEquals(Quaterniondc expected, Quaterniondc actual, double tolerance) {
        final double dot = expected.x() * actual.x()
                + expected.y() * actual.y()
                + expected.z() * actual.z()
                + expected.w() * actual.w();
        assertEquals(1.0, Math.abs(dot), Math.max(tolerance, 1.0e-9), "rotation");
    }
}
