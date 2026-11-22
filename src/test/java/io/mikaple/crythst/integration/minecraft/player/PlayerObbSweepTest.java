package io.mikaple.crythst.integration.minecraft.player;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure swept (time-of-impact) tests for the rotated player box.
 *
 * <p>The motivating case: a tick can move the body far enough to clear a thin
 * floor completely, so a post-move penetration test sees nothing on either side.
 * The sweep must report the contact in the middle of the motion instead.</p>
 */
class PlayerObbSweepTest {
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    private static final double EPS = 1.0e-9;

    private static Vector3d unit(double x, double y, double z) {
        return new Vector3d(x, y, z).normalize();
    }

    private static Aabb block(int x, int y, int z) {
        return new Aabb(
                new Vector3d(x, y, z),
                new Vector3d(x + 1.0, y + 1.0, z + 1.0)
        );
    }

    @Test
    void fastHorizontalMoveStopsAtTheWallFace() {
        // Feet at z = 0.5, body 0.6 wide; a wall fills z in [3, 4].
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));
        final Vector3d motion = new Vector3d(0.0, 0.0, 4.0);

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(
                from, motion, List.of(block(0, 4, 3)));

        assertNotNull(impact, "a 4-block move into a wall must be caught");
        // Gap from the box face (z = 0.8) to the wall (z = 3) is 2.2 of a 4.0 move.
        assertEquals(2.2 / 4.0, impact.time(), 1.0e-6, "impact time");
        assertEquals(-1.0, impact.normal().z, 1.0e-6, "normal points back along -Z");
        assertEquals(0.0, impact.normal().x, 1.0e-6);

        final Vector3d clamped = PlayerObbSweep.positionBeforeImpact(
                new Vector3d(0.5, 4.0, 0.5), motion, impact);
        assertEquals(0.5 + 2.2 - PlayerObbSweep.SKIN, clamped.z, 1.0e-6,
                "the body stops one skin off the wall face");
    }

    @Test
    void tunnellingThroughAThinFloorIsCaught() {
        // A single block floor at y = 4; the feet move from 6.0 to 1.0 in one tick.
        final OrientedBox from = PlayerObb.box(0.5, 6.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));
        final Vector3d motion = new Vector3d(0.0, -5.0, 0.0);

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(
                from, motion, List.of(block(0, 4, 0)));

        assertNotNull(impact, "the floor must be hit even though both end poses are clear");
        // Feet at 6.0 stop just above the block top (5.0): the skin is applied
        // along the contact normal, i.e. away from the obstacle.
        assertEquals(5.0 + PlayerObbSweep.SKIN,
                PlayerObbSweep.positionBeforeImpact(
                        new Vector3d(0.5, 6.0, 0.5), motion, impact).y,
                1.0e-6);
        assertEquals(1.0, impact.normal().y, 1.0e-6, "normal points up out of the floor");
    }

    @Test
    void rotatedGravitySweepsAgainstTheTerrainAxis() {
        // Gravity along -X, so the body extends along +X; a wall sits at x = 2.
        final Vector3d up = unit(1, 0, 0);
        final OrientedBox from = PlayerObb.box(0.0, 4.5, 0.5, WIDTH, HEIGHT, up);
        final Vector3d motion = new Vector3d(3.0, 0.0, 0.0);

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(
                from, motion, List.of(block(2, 4, 0)));

        assertNotNull(impact);
        assertTrue(impact.time() > 0.0 && impact.time() < 1.0, "impact inside the tick");
        assertEquals(-1.0, impact.normal().x, 1.0e-6);
    }

    @Test
    void freeMotionReportsNoImpact() {
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));

        assertNull(PlayerObbSweep.firstImpact(
                from, new Vector3d(0.0, 0.0, 0.1), List.of(block(0, 4, 3))));
        assertNull(PlayerObbSweep.firstImpact(
                from, new Vector3d(0.0, 0.0, 0.0), List.of(block(0, 4, 3))));
    }

    @Test
    void motionAwayFromAnObstacleIsFree() {
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));

        assertNull(PlayerObbSweep.firstImpact(
                from, new Vector3d(0.0, 0.0, -4.0), List.of(block(0, 4, 3))));
    }

    @Test
    void alreadyOverlappingObstacleIsLeftToThePenetrationPass() {
        // The body sits inside the wall: this is recovery, not tunnelling.
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 3.5, WIDTH, HEIGHT, unit(0, 1, 0));

        assertNull(PlayerObbSweep.firstImpact(
                from, new Vector3d(0.0, 0.0, 4.0), List.of(block(0, 4, 3))),
                "overlapping boxes belong to PlayerObbCollision's recovery path");
    }

    @Test
    void earliestOfTwoObstaclesWins() {
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));
        final Vector3d motion = new Vector3d(0.0, 0.0, 6.0);

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(
                from, motion, List.of(block(0, 4, 4), block(0, 4, 2)));

        assertNotNull(impact);
        assertEquals(2, (int) Math.floor(impact.obstacle().min().z), "the nearer wall wins");
    }

    @Test
    void diagonalMotionReportsTheFirstFace() {
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));
        final Vector3d motion = new Vector3d(4.0, 0.0, 4.0);

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(
                from, motion, List.of(block(3, 4, 0), block(0, 4, 3)));

        assertNotNull(impact);
        // Both walls are reached at the same instant from this symmetric start,
        // so the winner is whichever the list offers first; either face is valid.
        final Aabb hit = impact.obstacle();
        final boolean xWall = hit.min().x == 3.0 && hit.min().z == 0.0;
        final boolean zWall = hit.min().x == 0.0 && hit.min().z == 3.0;
        assertTrue(xWall || zWall, "must stop at one of the two walls, got " + hit);
        assertEquals(1.0, Math.abs(impact.normal().x) + Math.abs(impact.normal().z), 1.0e-6,
                "a face normal, not a diagonal one");
    }

    @Test
    void impactNormalIsUnitLength() {
        final OrientedBox from = PlayerObb.box(0.5, 4.0, 0.5, WIDTH, HEIGHT, unit(0, 1, 0));

        final PlayerObbSweep.Impact impact = PlayerObbSweep.firstImpact(
                from, new Vector3d(0.0, -5.0, 0.7), List.of(block(0, 2, 0)));

        assertNotNull(impact);
        assertEquals(1.0, impact.normal().length(), EPS);
        assertTrue(impact.time() >= 0.0 && impact.time() <= 1.0, "time is a tick fraction");
    }
}
