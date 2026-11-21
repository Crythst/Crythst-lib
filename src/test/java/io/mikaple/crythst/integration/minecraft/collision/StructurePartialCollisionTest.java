package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movement-level tests for structures whose collision geometry is fractional:
 * slabs, stairs and fence posts. These bypass Minecraft entirely and exercise
 * the same pure solver used by entity movement.
 */
class StructurePartialCollisionTest {
    private static VoxelCellPart part(double minX, double minY, double minZ,
                                      double sizeX, double sizeY, double sizeZ) {
        return new VoxelCellPart(minX, minY, minZ, sizeX, sizeY, sizeZ);
    }

    private static Aabb entityBox(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private static StructureCollisionSource source(Transform3d pose,
                                                   List<VoxelBox> boxes,
                                                   List<VoxelCellPart> parts,
                                                   double blockScale) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (VoxelBox box : boxes) {
            final double[] bounds = {box.minX(), box.minY(), box.minZ(),
                    box.minX() + box.sizeX(), box.minY() + box.sizeY(),
                    box.minZ() + box.sizeZ()};
            final double[] transformed = transformBounds(pose, bounds, blockScale);
            minX = Math.min(minX, transformed[0]);
            minY = Math.min(minY, transformed[1]);
            minZ = Math.min(minZ, transformed[2]);
            maxX = Math.max(maxX, transformed[3]);
            maxY = Math.max(maxY, transformed[4]);
            maxZ = Math.max(maxZ, transformed[5]);
        }
        for (VoxelCellPart part : parts) {
            final double[] bounds = {part.minX(), part.minY(), part.minZ(),
                    part.minX() + part.sizeX(), part.minY() + part.sizeY(),
                    part.minZ() + part.sizeZ()};
            final double[] transformed = transformBounds(pose, bounds, blockScale);
            minX = Math.min(minX, transformed[0]);
            minY = Math.min(minY, transformed[1]);
            minZ = Math.min(minZ, transformed[2]);
            maxX = Math.max(maxX, transformed[3]);
            maxY = Math.max(maxY, transformed[4]);
            maxZ = Math.max(maxZ, transformed[5]);
        }
        final StructureCollisionBox structure = new StructureCollisionBox(
                1L, pose, boxes, blockScale,
                new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ)),
                parts);
        return new StructureCollisionSource() {
            @Override
            public List<StructureCollisionBox> candidates(Aabb query) {
                return structure.worldBounds().intersects(query)
                        ? List.of(structure) : List.of();
            }

            @Override
            public double maxCorrection() {
                return 4.0;
            }

            @Override
            public String side() {
                return "test";
            }
        };
    }

    private static double[] transformBounds(Transform3d pose, double[] bounds, double scale) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d point = pose.transformPoint(new Vector3d(
                    ((corner & 1) == 0 ? bounds[0] : bounds[3]) * scale,
                    ((corner & 2) == 0 ? bounds[1] : bounds[4]) * scale,
                    ((corner & 4) == 0 ? bounds[2] : bounds[5]) * scale));
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static StructureMotionSolver.Result solve(Aabb box, Vector3d motion,
                                                     boolean onGround, double stepHeight,
                                                     StructureCollisionSource source) {
        return StructureMotionSolver.solve(
                new StructureMotionSolver.Input(box, motion, stepHeight, onGround, motion.y, true),
                source);
    }

    @Test
    void slabSupportsAnEntityAtHalfHeight() {
        final StructureCollisionSource slab = source(Transform3d.identity(), List.of(),
                List.of(part(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)), 1.0);

        final StructureMotionSolver.Result result = solve(
                entityBox(0.2, 0.55, 0.2, 0.8, 2.35, 0.8),
                new Vector3d(0.0, -0.1, 0.0),
                true, 0.6, slab);

        assertTrue(result.touched());
        assertTrue(result.support(), "a slab top must be a support surface: " + result);
        assertTrue(result.contacts().get(0).normal().y > 0.99);
        assertEquals(-0.05, result.motion().y, 2.0e-2,
                "feet must settle on the half-height surface, not the full cube top");
        assertEquals(0.0, result.motion().x, 1.0e-4);
        assertEquals(0.0, result.motion().z, 1.0e-4);
    }

    @Test
    void entityAboveTheSlabSurfaceWalksAcrossWithoutAFullCubeWall() {
        final StructureCollisionSource slab = source(Transform3d.identity(), List.of(),
                List.of(part(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)), 1.0);

        // Feet are above the slab top (0.5) but below a full cube's top (1.0).
        final StructureMotionSolver.Result result = solve(
                entityBox(-0.5, 0.55, 0.2, 0.1, 2.35, 0.8),
                new Vector3d(0.5, 0.0, 0.0),
                true, 0.6, slab);

        assertFalse(result.touched(),
                "a full-cube solver would stop here; exact parts must let it pass");
        assertEquals(0.5, result.motion().x, 1.0e-6);
    }

    @Test
    void stairUpperStepIsSteppedOnto() {
        final List<VoxelCellPart> stairs = List.of(
                part(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                part(0.5, 0.5, 0.0, 0.5, 0.5, 1.0));
        final StructureCollisionSource stair = source(Transform3d.identity(), List.of(),
                stairs, 1.0);

        final StructureMotionSolver.Result result = solve(
                entityBox(-0.1, 0.5, 0.2, 0.5, 2.3, 0.8),
                new Vector3d(0.2, -0.02, 0.0),
                true, 0.6, stair);

        assertTrue(result.stepped(), "the 0.5 m stair step must be climbed: " + result);
        assertTrue(result.motion().y > 0.4, "step lift is part of the allowed motion: "
                + result.motion());
        assertTrue(result.motion().x > 0.0, "forward motion continues after the step");
    }

    @Test
    void fencePostBlocksOnlyItsThinFootprint() {
        final List<VoxelCellPart> post =
                List.of(part(0.375, 0.0, 0.375, 0.25, 1.5, 0.25));
        final StructureCollisionSource fence = source(Transform3d.identity(), List.of(),
                post, 1.0);

        final StructureMotionSolver.Result blocked = solve(
                entityBox(-0.4, 0.0, 0.4, 0.2, 1.8, 0.6),
                new Vector3d(0.5, 0.0, 0.0),
                false, 0.6, fence);

        assertTrue(blocked.touched(), "walking into a fence post must collide");
        assertTrue(blocked.horizontalCollision());
        assertTrue(blocked.motion().x > 0.1 && blocked.motion().x < 0.25,
                "the thin post must stop the entity at its footprint: "
                        + blocked.motion());

        final StructureMotionSolver.Result clear = solve(
                entityBox(-0.4, 0.0, 0.7, 0.2, 1.8, 1.3),
                new Vector3d(0.5, 0.0, 0.0),
                false, 0.6, fence);

        assertFalse(clear.touched(),
                "the space beside the post must stay clear: " + clear.motion());
        assertEquals(0.5, clear.motion().x, 1.0e-6);
    }

    @Test
    void rotatedAndScaledSlabKeepsItsExactTop() {
        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(90.0));
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 0.0, 0.0), rotation);
        final StructureCollisionSource scaled = source(pose, List.of(),
                List.of(part(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)), 0.5);

        // Local X maps to world +Z and local Z to world -X at a 90 degree yaw.
        final StructureMotionSolver.Result result = solve(
                entityBox(10.05, 0.3, -0.45, 10.45, 1.8, -0.05),
                new Vector3d(0.0, -0.1, 0.0),
                true, 0.6, scaled);

        assertTrue(result.touched());
        assertTrue(result.support(), "rotated/scaled slab must still support: " + result);
        assertTrue(result.contacts().get(0).normal().y > 0.9);
        assertEquals(-0.05, result.motion().y, 2.0e-2,
                "support height must follow the transformed half-block top");
    }

    @Test
    void fullCubeBoxesKeepExactFastPathWithoutParts() {
        final VoxelBox cube = new VoxelBox(0, 0, 0, 1, 1, 1, PhysicsMaterial.STONE, 1.0);
        final StructureCollisionSource cubeOnly = source(Transform3d.identity(),
                List.of(cube), List.of(), 1.0);

        final StructureMotionSolver.Result result = solve(
                entityBox(0.2, 1.05, 0.2, 0.8, 2.85, 0.8),
                new Vector3d(0.0, -0.2, 0.0),
                true, 0.6, cubeOnly);

        assertTrue(result.touched());
        assertTrue(result.support());
        assertEquals(-0.05, result.motion().y, 1.0e-3);
    }
}
