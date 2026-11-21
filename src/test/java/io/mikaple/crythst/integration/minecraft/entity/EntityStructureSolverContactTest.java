package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contact-selection tests for the gameplay policy: support landings, resting
 * slop, and the previous-centre hint. These are the behaviours that decide
 * whether a player slides, stands, or gets shoved sideways.
 */
class EntityStructureSolverContactTest {
    private static final double MARGIN = 0.02;
    private static final double SLOP = 2.0e-3;

    private static VoxelBox box(int x, int y, int z, int sx, int sy, int sz) {
        return new VoxelBox(x, y, z, sx, sy, sz, PhysicsMaterial.STONE, 1.0);
    }

    private static Aabb entityBox(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private static EntityStructureSolver.Policy gameplay(boolean support, double verticalSpeed) {
        return EntityStructureSolver.Policy.gameplay(
                MARGIN, 64, SLOP, support, verticalSpeed);
    }

    @Test
    void landingOnFloorEdgeResolvesUpInsteadOfSideways() {
        // 15 cm below the floor top, but only 8 cm past its edge. The legacy
        // shallowest-axis rule pushes +X (off the platform); the support rule
        // must stand the player on top.
        final Aabb player = entityBox(0.92, 0.85, 0.2, 1.52, 1.65, 0.8);
        final List<VoxelBox> floor = List.of(box(0, 0, 0, 1, 1, 1));

        final var legacy = EntityStructureSolver.resolve(
                player, Transform3d.identity(), floor, 1.0, MARGIN, 64);
        assertNotNull(legacy);
        assertTrue(legacy.normal().x > 0.99,
                "legacy behaviour should be the sideways shove: " + legacy.normal());

        final var hit = EntityStructureSolver.resolve(
                player, Transform3d.identity(), floor, 1.0, gameplay(true, -0.5));
        assertNotNull(hit);
        assertTrue(hit.support(), "expected a support contact: " + hit);
        assertTrue(hit.normal().y > 0.99, "expected an upward exit: " + hit.normal());
        assertEquals(0.15, hit.penetration(), 1.0e-6);
        assertEquals(0.0, hit.offset().x, 1.0e-9);
    }

    @Test
    void tallWallIsStillPushedOutHorizontally() {
        // The wall spans the whole body, so its vertical overlap is far too
        // deep to be a step; the support preference must not apply.
        final Aabb player = entityBox(0.9, 0.0, 0.2, 1.5, 1.8, 0.8);
        final var hit = EntityStructureSolver.resolve(
                player, Transform3d.identity(),
                List.of(box(1, 0, 0, 1, 3, 1)), 1.0, gameplay(true, -0.078));
        assertNotNull(hit);
        assertFalse(hit.support());
        assertTrue(hit.normal().x < -0.99, "expected -X exit: " + hit.normal());
        assertEquals(0.5, hit.penetration(), 1.0e-6);
    }

    @Test
    void shallowRestingContactIsIgnored() {
        final Aabb player = entityBox(0.2, 0.9995, 0.2, 0.8, 1.7995, 0.8);
        assertNull(EntityStructureSolver.resolve(
                player, Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)), 1.0, gameplay(true, 0.0)),
                "sub-slop contact must not produce a correction");
    }

    @Test
    void supportNeedsAGroundedOrDescendingCaller() {
        final Aabb player = entityBox(0.92, 0.85, 0.2, 1.52, 1.65, 0.8);
        final var hit = EntityStructureSolver.resolve(
                player, Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)), 1.0, gameplay(false, 0.4));
        assertNotNull(hit);
        assertFalse(hit.support());
        assertTrue(hit.normal().x > 0.99, "ascending caller keeps legacy exit: " + hit.normal());
    }

    @Test
    void fastFallOnACornerStillResolvesUp() {
        // Feet are 0.6 m inside the floor but the body is only 0.02 m past the
        // edge. A fast landing gets a larger support budget so the shallow
        // horizontal exit cannot shove the player off.
        final Aabb player = entityBox(0.98, 0.4, 0.2, 1.58, 1.2, 0.8);
        final var hit = EntityStructureSolver.resolve(
                player, Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)), 1.0, gameplay(true, -3.0));
        assertNotNull(hit);
        assertTrue(hit.support());
        assertTrue(hit.normal().y > 0.99, "expected upward exit: " + hit.normal());
        assertEquals(0.6, hit.penetration(), 1.0e-6);
    }

    @Test
    void supportWinsOverShallowerHorizontalFromAnotherBox() {
        final Aabb player = entityBox(0.98, 0.85, 0.2, 1.58, 1.65, 0.8);
        final var hit = EntityStructureSolver.resolve(
                player, Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1), box(1, 0, 0, 1, 3, 1)),
                1.0, gameplay(true, -0.5));
        assertNotNull(hit);
        assertTrue(hit.support());
        assertTrue(hit.normal().y > 0.99, "expected upward exit: " + hit.normal());
    }

    @Test
    void hintDoesNotFlipTheSignWhenTheCentreIsOutsideTheBox() {
        final Aabb player = entityBox(0.85, 0.1, 0.2, 1.45, 1.9, 0.8);
        final List<VoxelBox> wall = List.of(box(0, 0, 0, 1, 2, 1));

        // Current centre is +0.65 from the box centre and therefore outside the
        // wall slab. A stale hint from the far side must not push it back in.
        final var hit = EntityStructureSolver.resolve(
                player, new Vector3d(0.2, 1.0, 0.5),
                Transform3d.identity(), wall, 1.0, gameplay(false, 0.0));
        assertNotNull(hit);
        assertTrue(hit.normal().x > 0.99,
                "current side must win once the centre is outside: " + hit.normal());
        assertTrue(hit.offset().x > 0.0);
    }

    @Test
    void hintStillPreventsTunnellingWhenTheCentreIsInsideTheBox() {
        final Aabb player = entityBox(0.3, 0.1, 0.2, 0.9, 1.9, 0.8);
        final var hit = EntityStructureSolver.resolve(
                player, new Vector3d(0.4, 1.0, 0.5),
                Transform3d.identity(), List.of(box(0, 0, 0, 1, 2, 1)),
                1.0, gameplay(false, 0.0));
        assertNotNull(hit);
        assertTrue(hit.normal().x < -0.99, "hint should return the entry side: " + hit.normal());
    }

    @Test
    void supportOffsetActuallySeparatesThePlayer() {
        final Aabb player = entityBox(0.92, 0.85, 0.2, 1.52, 1.65, 0.8);
        final List<VoxelBox> floor = List.of(box(0, 0, 0, 1, 1, 1));
        final var hit = EntityStructureSolver.resolve(
                player, Transform3d.identity(), floor, 1.0, gameplay(true, -0.5));
        assertNotNull(hit);
        assertNull(EntityStructureSolver.resolve(
                player.offset(hit.offset()), Transform3d.identity(), floor, 1.0,
                EntityStructureSolver.Policy.legacy(0.0, 64)),
                "the corrected box must no longer overlap");
    }
}
