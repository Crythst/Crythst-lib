package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movement-level tests for the structure collision kernel.
 *
 * <p>These tests never touch Minecraft: they exercise the same pure geometry
 * that {@code Entity.move} calls, so a regression in sliding, support, ceiling
 * handling or tunneling is visible without a game client.</p>
 */
class StructureMotionSolverTest {
    private static final double EPS = 1.0e-6;

    private static VoxelBox box(int x, int y, int z, int sx, int sy, int sz) {
        return new VoxelBox(x, y, z, sx, sy, sz, PhysicsMaterial.STONE, 1.0);
    }

    private static Aabb entityBox(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private static StructureCollisionSource source(Transform3d pose,
                                                   List<VoxelBox> boxes,
                                                   double blockScale) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (VoxelBox box : boxes) {
            for (int corner = 0; corner < 8; corner++) {
                final Vector3d point = pose.transformPoint(new Vector3d(
                        ((corner & 1) == 0 ? box.minX() : box.minX() + box.sizeX()) * blockScale,
                        ((corner & 2) == 0 ? box.minY() : box.minY() + box.sizeY()) * blockScale,
                        ((corner & 4) == 0 ? box.minZ() : box.minZ() + box.sizeZ()) * blockScale
                ));
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                minZ = Math.min(minZ, point.z);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                maxZ = Math.max(maxZ, point.z);
            }
        }
        final StructureCollisionBox structure = new StructureCollisionBox(
                1L, pose, boxes, blockScale,
                new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ)));
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

    private static StructureMotionSolver.Result solve(Aabb box, Vector3d motion,
                                                     boolean onGround, double stepHeight,
                                                     StructureCollisionSource source) {
        return StructureMotionSolver.solve(
                new StructureMotionSolver.Input(box, motion, stepHeight, onGround, motion.y, true),
                source);
    }

    @Test
    void wallStopsIntoSurfaceMotionAndPreservesTangentialMotion() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 2, 2)), 1.0);
        final StructureMotionSolver.Result result = solve(
                entityBox(1.05, 0.0, 0.2, 1.65, 1.8, 0.8),
                new Vector3d(-0.3, 0.0, 0.2),
                false, 0.6, source);

        assertTrue(result.touched());
        assertTrue(result.horizontalCollision());
        assertTrue(result.motion().x <= 0.001, "must not cross the wall: " + result.motion());
        assertTrue(result.motion().x >= -0.06, "must not be flung away: " + result.motion());
        assertEquals(0.2, result.motion().z, 1.0e-3, "tangential motion is preserved");
        assertEquals(1.0, result.contacts().get(0).normal().x, 0.2);
    }

    @Test
    void floorProvidesSupportWithoutSidewaysPush() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(box(0, 0, 0, 4, 1, 4)), 1.0);
        final StructureMotionSolver.Result result = solve(
                entityBox(0.2, 1.05, 0.2, 0.8, 1.9, 0.8),
                new Vector3d(0.0, -0.2, 0.0),
                true, 0.6, source);

        assertTrue(result.touched());
        assertTrue(result.support());
        assertTrue(result.verticalCollisionBelow());
        assertTrue(result.contacts().get(0).normal().y > 0.99);
        assertEquals(-0.05, result.motion().y, 1.0e-3);
        assertEquals(0.0, result.motion().x, 1.0e-4);
        assertEquals(0.0, result.motion().z, 1.0e-4);
    }

    @Test
    void ceilingStopsUpwardMotion() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(box(0, 2, 0, 4, 1, 4)), 1.0);
        final StructureMotionSolver.Result result = solve(
                entityBox(0.2, 0.1, 0.2, 0.8, 1.9, 0.8),
                new Vector3d(0.0, 0.5, 0.0),
                false, 0.6, source);

        assertTrue(result.touched());
        assertTrue(result.verticalCollision());
        assertFalse(result.verticalCollisionBelow());
        assertTrue(result.contacts().get(0).normal().y < -0.99);
        assertEquals(0.1, result.motion().y, 1.0e-3);
    }

    @Test
    void rotatedFloorIsRecognisedAsSupport() {
        final Quaterniond rotation = new Quaterniond().rotateZ(Math.toRadians(8.0));
        final Transform3d pose = Transform3d.of(new Vector3d(0.0, -0.2, 0.0), rotation);
        final StructureCollisionSource source = source(pose,
                List.of(box(-8, 0, -8, 16, 1, 16)), 1.0);
        final StructureMotionSolver.Result result = solve(
                entityBox(0.0, 1.0, 0.0, 0.6, 2.8, 0.6),
                new Vector3d(0.0, -0.4, 0.0),
                true, 0.6, source);

        assertTrue(result.touched());
        assertTrue(result.support());
        final Vector3d normal = result.contacts().get(0).normal();
        assertTrue(normal.y > 0.8, "rotated floor normal should still point up: " + normal);
    }

    @Test
    void structureStepUpLiftsOntoLowLedge() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(
                        box(0, 0, 0, 8, 1, 8),
                        box(0, 1, 0, 1, 1, 8)
                ), 0.5);
        final StructureMotionSolver.Result result = solve(
                entityBox(0.51, 0.5, 0.2, 1.11, 2.3, 0.8),
                new Vector3d(-0.2, -0.02, 0.0),
                true, 0.6, source);

        assertTrue(result.stepped(), "a 0.5 m ledge must be stepped onto");
        assertTrue(result.motion().y > 0.5, "step lift is part of the allowed motion: "
                + result.motion());
        assertTrue(result.motion().x < 0.0, "forward motion continues after the step");
    }

    @Test
    void zeroStepHeightEntityDoesNotClimbTheSameLedge() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(
                        box(0, 0, 0, 8, 1, 8),
                        box(0, 1, 0, 1, 1, 8)
                ), 0.5);
        final StructureMotionSolver.Result result = solve(
                entityBox(0.51, 0.5, 0.2, 1.11, 2.3, 0.8),
                new Vector3d(-0.2, -0.02, 0.0),
                true, 0.0, source);

        assertFalse(result.stepped(), "step height zero must not step");
        assertTrue(result.motion().y < 0.05,
                "must not be lifted over the wall: " + result.motion());
        assertTrue(result.motion().x > -0.1,
                "horizontal motion must be blocked: " + result.motion());
    }

    @Test
    void embeddedEntityIsPushedOutOnce() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)), 1.0);
        final StructureMotionSolver.Result result = solve(
                entityBox(0.2, 0.1, 0.2, 0.8, 1.9, 0.8),
                new Vector3d(),
                false, 0.6, source);

        assertTrue(result.touched());
        assertTrue(result.motion().length() > 0.5,
                "an embedded entity must be separated, not nudged: " + result.motion());
    }

    @Test
    void sourceCorrectionCapLimitsStalePoseFlings() {
        final StructureCollisionBox structure = new StructureCollisionBox(
                7L, Transform3d.identity(), List.of(box(0, 0, 0, 1, 1, 1)), 1.0,
                new Aabb(new Vector3d(0, 0, 0), new Vector3d(1, 1, 1)));
        final StructureCollisionSource capped = new StructureCollisionSource() {
            @Override
            public List<StructureCollisionBox> candidates(Aabb query) {
                return List.of(structure);
            }

            @Override
            public double maxCorrection() {
                return 0.1;
            }

            @Override
            public String side() {
                return "test";
            }
        };
        final StructureMotionSolver.Result result = solve(
                entityBox(0.2, 0.1, 0.2, 0.8, 1.9, 0.8),
                new Vector3d(),
                false, 0.6, capped);

        assertTrue(result.touched());
        assertTrue(result.motion().length() <= 0.1 + 1.0e-6,
                "correction must respect the source cap: " + result.motion());
    }

    @Test
    void fastEntityDoesNotTunnelThroughAThinWall() {
        final StructureCollisionSource source = source(Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 2, 2)), 1.0);
        final StructureMotionSolver.Result result = solve(
                entityBox(-0.8, 0.1, 0.2, -0.2, 1.9, 0.8),
                new Vector3d(3.0, 0.0, 0.0),
                false, 0.6, source);

        assertTrue(result.touched());
        assertTrue(result.motion().x < 0.25,
                "high-speed motion must stop at the wall: " + result.motion());
        assertTrue(result.motion().x > -0.2,
                "the entity must not be pushed through the far side: " + result.motion());
    }

    @Test
    void contactNormalsOnlyRemoveInwardVelocity() {
        final List<StructureMotionSolver.Contact> contacts = List.of(
                new StructureMotionSolver.Contact(1L, new Vector3d(-1.0, 0.0, 0.0), 0.1));
        final Vector3d filtered = StructureMotionSolver.filterVelocity(
                new Vector3d(0.3, -0.1, 0.2), contacts);
        assertEquals(0.0, filtered.x, EPS);
        assertEquals(-0.1, filtered.y, EPS);
        assertEquals(0.2, filtered.z, EPS);
    }

    @Test
    void noStructureKeepsTheExactMotion() {
        final StructureCollisionSource empty = new StructureCollisionSource() {
            @Override
            public List<StructureCollisionBox> candidates(Aabb query) {
                return List.of();
            }

            @Override
            public double maxCorrection() {
                return 1.0;
            }

            @Override
            public String side() {
                return "test";
            }
        };
        final Vector3d motion = new Vector3d(0.12, -0.08, -0.03);
        final StructureMotionSolver.Result result = solve(
                entityBox(10.0, 10.0, 10.0, 10.6, 11.8, 10.6),
                motion, true, 0.6, empty);
        assertEquals(motion.x, result.motion().x, EPS);
        assertEquals(motion.y, result.motion().y, EPS);
        assertEquals(motion.z, result.motion().z, EPS);
        assertFalse(result.touched());
    }

    @Test
    void substepsAreBoundedAndLocalPlayerUsesEight() {
        assertEquals(8, StructureMotionSolver.substeps(new Vector3d(0.01, 0.0, 0.0), true));
        assertEquals(1, StructureMotionSolver.substeps(new Vector3d(0.0, 0.0, 0.0), false));
        assertEquals(StructureMotionSolver.MAX_SUBSTEPS,
                StructureMotionSolver.substeps(new Vector3d(10.0, 0.0, 0.0), false));
        assertNotNull(StructureMotionSolver.substeps(new Vector3d(0.02, 0.0, 0.0), false));
    }
}
