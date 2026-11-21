package io.mikaple.crythst.integration.minecraft.entity.client;

import io.mikaple.crythst.integration.minecraft.entity.EntityStructureSolver;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tick-loop simulation of a player against structure boxes.
 *
 * <p>This deliberately runs the same pure resolver that the client and server
 * use, with Minecraft-like gravity (applied every tick), then checks the
 * properties that make contact feel non-sticky: tangential motion is kept, the
 * exit normal is stable, and resting on a floor does not push sideways.</p>
 */
class StructureCollisionContactSimulationTest {
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double MARGIN = 0.02;
    private static final double SLOP = 2.0e-3;

    private static VoxelBox box(int x, int y, int z, int sx, int sy, int sz) {
        return new VoxelBox(x, y, z, sx, sy, sz, PhysicsMaterial.STONE, 1.0);
    }

    /** A minimal feet-anchored player under structure-only collision. */
    private static final class Player {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        boolean onGround;

        Player(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Aabb box() {
            return new Aabb(
                    new Vector3d(x - HALF_WIDTH, y, z - HALF_WIDTH),
                    new Vector3d(x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH));
        }

        ClientStructureCollisionResolver.Correction tick(List<VoxelBox> boxes) {
            final Vector3d previousCenter = new Vector3d(x, y + HEIGHT * 0.5, z);
            // Minecraft applies gravity on every tick, including while resting
            // on a surface; collision resolution is what removes the motion.
            vy = (vy - GRAVITY) * DRAG;
            x += vx;
            y += vy;
            z += vz;

            final EntityStructureSolver.Policy policy = EntityStructureSolver.Policy.gameplay(
                    MARGIN, 64, SLOP, onGround || vy <= 0.0, vy);
            final ClientStructureCollisionResolver.Correction correction =
                    ClientStructureCollisionResolver.resolve(
                            box(), previousCenter,
                            List.of(new ClientStructureCollisionResolver.Collider(
                                    Transform3d.identity(), boxes, 1.0)),
                            policy,
                            ClientStructureCollisionResolver.DEFAULT_MAX_PASSES,
                            ClientStructureCollisionResolver.DEFAULT_MAX_CORRECTION);

            if (correction == null) {
                onGround = false;
                return null;
            }
            final Vector3d offset = correction.offset();
            x += offset.x;
            y += offset.y;
            z += offset.z;

            for (Vector3d normal : correction.contacts()) {
                final double into = vx * normal.x + vy * normal.y + vz * normal.z;
                if (into < 0.0) {
                    vx -= normal.x * into;
                    vy -= normal.y * into;
                    vz -= normal.z * into;
                }
            }
            if (correction.support() || correction.normal().y > 0.5) {
                onGround = true;
            }
            return correction;
        }
    }

    @Test
    void standingOnAFloorKeepsThePlayerAtTheSurfaceWithoutHorizontalPush() {
        final List<VoxelBox> floor = List.of(box(0, 0, 0, 32, 1, 32));
        final Player player = new Player(5.0, 1.0, 5.0);
        player.onGround = true;

        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 60; i++) {
            final var correction = player.tick(floor);
            assertNotNull(correction, "resting player should be supported every tick");
            assertTrue(correction.normal().y > 0.99,
                    "floor contact must not push sideways: " + correction.normal());
            assertTrue(correction.support());
            assertEquals(5.0, player.x, 1.0e-9);
            assertEquals(5.0, player.z, 1.0e-9);
            minY = Math.min(minY, player.y);
            maxY = Math.max(maxY, player.y);
        }
        // The tick-boundary position is stable: the visible bounce is gone.
        assertTrue(maxY - minY < 1.0e-6,
                "resting height drifted between " + minY + " and " + maxY);
        assertEquals(1.0 + MARGIN, maxY, 1.0e-6);
    }

    @Test
    void walkingOnAFloorPreservesTangentialVelocity() {
        final List<VoxelBox> floor = List.of(box(0, 0, 0, 32, 1, 32));
        final Player player = new Player(5.0, 1.0, 5.0);
        player.onGround = true;
        player.vx = 0.15;

        for (int i = 0; i < 40; i++) {
            final var correction = player.tick(floor);
            assertNotNull(correction);
            assertTrue(correction.normal().y > 0.99);
        }
        assertEquals(0.15, player.vx, 1.0e-9, "walking speed must survive support");
        assertEquals(5.0 + 40 * 0.15, player.x, 1.0e-6);
        assertTrue(player.y > 1.0 && player.y < 1.1);
    }

    @Test
    void wallContactRemovesOnlyTheIntoWallComponent() {
        final List<VoxelBox> boxes = List.of(
                box(0, 0, 0, 32, 1, 32),   // floor
                box(2, 1, 0, 1, 4, 32));   // wall on +X
        final Player player = new Player(1.7, 1.0, 5.0);
        player.onGround = true;
        player.vx = 0.1;
        player.vz = 0.08;

        final List<Vector3d> normals = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // Hold "forward" into the wall every tick, like a key press; the
            // tangential component is never re-applied, so it must survive.
            player.vx = 0.1;
            final var correction = player.tick(boxes);
            assertNotNull(correction);
            normals.add(correction.normal());
            assertTrue(correction.normal().x < -0.99,
                    "wall normal must stay -X, got " + correction.normal());
            assertTrue(player.x <= 1.7 + 1.0e-6,
                    "player must not creep into the wall: " + player.x);
        }
        // Tangential motion along the wall is untouched.
        assertEquals(0.08, player.vz, 1.0e-9);
        assertEquals(5.0 + 40 * 0.08, player.z, 1.0e-6);
        assertEquals(0.0, player.vx, 1.0e-9, "into-wall velocity is removed");

        assertStableNormals(normals);
    }

    @Test
    void jumpingAgainstACeilingStopsAndDoesNotAlternate() {
        final List<VoxelBox> boxes = List.of(
                box(0, 0, 0, 32, 1, 32),   // floor
                box(0, 3, 0, 32, 1, 32));  // ceiling, 0.2 m of headroom
        final Player player = new Player(5.0, 1.0, 5.0);
        player.onGround = true;
        player.vy = 0.42;

        Vector3d ceilingNormal = null;
        for (int i = 0; i < 20 && ceilingNormal == null; i++) {
            final var correction = player.tick(boxes);
            if (correction != null && correction.normal().y < -0.99) {
                ceilingNormal = correction.normal();
            }
        }
        assertNotNull(ceilingNormal, "jump should hit the ceiling");
        assertTrue(player.vy <= 0.0, "upward velocity must be removed");
        assertTrue(player.y + HEIGHT <= 3.0 - MARGIN + 1.0e-6);
    }

    @Test
    void landingOnTheEdgeOfAFloorStandsOnTopInsteadOfSlidingOff() {
        final List<VoxelBox> floor = List.of(box(0, 0, 0, 1, 1, 1));
        final Player player = new Player(1.25, 1.3, 0.5);
        player.vy = -0.5;

        final var correction = player.tick(floor);
        assertNotNull(correction);
        assertTrue(correction.support(), "edge landing should resolve up: " + correction);
        assertTrue(correction.normal().y > 0.99);
        assertEquals(1.25, player.x, 1.0e-9, "must not be shoved off sideways");
        assertEquals(1.0 + MARGIN, player.y, 1.0e-6);
    }

    @Test
    void diagonalApproachKeepsBothTangentialComponents() {
        final List<VoxelBox> boxes = List.of(
                box(0, 0, 0, 32, 1, 32),
                box(2, 1, 0, 1, 4, 32));
        final Player player = new Player(1.7, 1.0, 5.0);
        player.onGround = true;
        player.vx = 0.12;
        player.vz = 0.09;

        for (int i = 0; i < 25; i++) {
            assertNotNull(player.tick(boxes));
        }
        assertEquals(0.0, player.vx, 1.0e-9);
        assertEquals(0.09, player.vz, 1.0e-9);
        assertEquals(5.0 + 25 * 0.09, player.z, 1.0e-6);
    }

    @Test
    void restingContactBelowSlopIsNotCorrected() {
        final var correction = ClientStructureCollisionResolver.resolve(
                new Aabb(new Vector3d(0.2, 1.0005, 0.2),
                        new Vector3d(0.8, 1.8005, 0.8)),
                null,
                new ClientStructureCollisionResolver.Collider(
                        Transform3d.identity(), List.of(box(0, 0, 0, 1, 1, 1)), 1.0),
                EntityStructureSolver.Policy.gameplay(MARGIN, 64, SLOP, true, 0.0),
                ClientStructureCollisionResolver.DEFAULT_MAX_CORRECTION);
        assertNull(correction, "a 0.5 mm overlap is resting contact");
    }

    @Test
    void jumpingFromAFloorIsNotBlockedBySupport() {
        final List<VoxelBox> floor = List.of(box(0, 0, 0, 32, 1, 32));
        final Player player = new Player(5.0, 1.0, 5.0);
        player.onGround = true;
        player.vy = 0.42;

        final double startY = player.y;
        player.tick(floor);
        assertTrue(player.y > startY + 0.2, "jump should leave the floor");
        assertTrue(player.vy > 0.2);
        assertFalse(player.onGround);
    }

    @Test
    void aSingleGrazingWallTickDoesNotStartAShoveLoop() {
        final List<VoxelBox> boxes = List.of(box(0, 0, 0, 1, 3, 1));
        final Player player = new Player(1.0 + HALF_WIDTH - 0.01, 0.0, 0.5);
        player.onGround = false;
        player.vy = 0.0;

        final var first = player.tick(boxes);
        assertNotNull(first);
        assertTrue(first.normal().x > 0.99, "exit must point away from the wall: " + first.normal());
        // After the correction the player is clear, so the next tick must not
        // produce an opposite-sign correction.
        final var second = player.tick(boxes);
        if (second != null) {
            assertTrue(second.normal().dot(first.normal()) > 0.99,
                    "contact normals must not alternate: " + first + " then " + second);
        }
    }

    private static void assertStableNormals(List<Vector3d> normals) {
        assertFalse(normals.isEmpty());
        final Vector3d first = normals.get(0);
        for (Vector3d normal : normals) {
            assertTrue(first.dot(normal) > 0.99,
                    "exit normal flipped between ticks: " + first + " vs " + normal);
        }
    }
}
