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
 * Pure anchor and terrain-push tests for the rotated player box.
 *
 * <p>Under directional gravity {@code Minecraft.position()} is the feet anchor
 * in the gravity frame. The body extends along {@code up}, so the OBB centre is
 * {@code position + up * (height / 2)} and the feet point
 * {@code centre - up * (height / 2)} stays exactly at the entity position for
 * every gravity direction.</p>
 */
class PlayerObbTest {
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    private static final double CROUCH_HEIGHT = 1.5;
    private static final double EPS = 1.0e-9;

    private static Vector3d unit(double x, double y, double z) {
        return new Vector3d(x, y, z).normalize();
    }

    @Test
    void vanillaUpKeepsTheFeetAnchor() {
        final OrientedBox box = PlayerObb.box(3.0, 4.0, 5.0, WIDTH, HEIGHT, unit(0, 1, 0));

        assertEquals(3.0, box.center().x, EPS);
        assertEquals(5.0, box.center().z, EPS);
        assertEquals(4.0 + HEIGHT * 0.5, box.center().y, EPS);
        assertEquals(HEIGHT * 0.5, box.halfExtents().y, EPS);
        assertEquals(WIDTH * 0.5, box.halfExtents().x, EPS);
        assertEquals(3.0, box.center().x - box.axes()[1].x * box.halfExtents().y, EPS);
        assertEquals(4.0, box.center().y - box.axes()[1].y * box.halfExtents().y, EPS);
        assertEquals(5.0, box.center().z - box.axes()[1].z * box.halfExtents().y, EPS);
    }

    @Test
    void rotatedUpExtendsTheBoxAlongTheGravityAxis() {
        final Vector3d[] ups = {
                unit(1, 0, 0),
                unit(0, 0, 1),
                unit(-1, 0, 0),
                unit(0, 0, -1),
                unit(0.6, 0.8, 0.0),
                unit(-0.3, 0.4, 0.8660254037844386)
        };
        for (Vector3d up : ups) {
            final Vector3d position = new Vector3d(7.0, 2.0, -4.0);
            final OrientedBox box = PlayerObb.box(position.x, position.y, position.z,
                    WIDTH, HEIGHT, up);
            final Vector3d center = box.center();

            assertVectorEquals(new Vector3d(position).fma(HEIGHT * 0.5, up), center,
                    "up " + up + " must place the centre at position + up * halfHeight");

            // The feet point is fixed: centre - up * halfHeight == position.
            final Vector3d feet = new Vector3d(center).fma(-HEIGHT * 0.5, up);
            assertVectorEquals(position, feet,
                    "up " + up + " must not move the feet anchor");

            // Local +Y (the OBB's second axis) follows gravity up.
            assertVectorEquals(up, box.axes()[1],
                    "up " + up + " must become the box local +Y");
        }
    }

    @Test
    void crouchHeightMovesTheCentreAlongTheGravityAxis() {
        final Vector3d up = unit(0, 1, 0);
        final OrientedBox standing = PlayerObb.box(1.0, 2.0, 3.0,
                WIDTH, HEIGHT, up);
        final OrientedBox crouched = PlayerObb.box(1.0, 2.0, 3.0,
                WIDTH, CROUCH_HEIGHT, up);

        assertEquals(2.0 + HEIGHT * 0.5, standing.center().y, EPS);
        assertEquals(2.0 + CROUCH_HEIGHT * 0.5, crouched.center().y, EPS);
        assertEquals(0.0, standing.center().y - HEIGHT * 0.5 - 2.0, EPS);
        assertEquals(0.0, crouched.center().y - CROUCH_HEIGHT * 0.5 - 2.0, EPS);
    }

    @Test
    void localUpAxisFollowsGravityUp() {
        final Vector3d up = unit(0.3, 0.4, 0.8660254037844386);
        final OrientedBox box = PlayerObb.box(0.0, 0.0, 0.0, WIDTH, HEIGHT, up);
        final Vector3d axis = box.axes()[1];

        assertEquals(up.x, axis.x, 1.0e-9);
        assertEquals(up.y, axis.y, 1.0e-9);
        assertEquals(up.z, axis.z, 1.0e-9);
    }

    @Test
    void rotatedPlayerIsPushedOutOfTerrainAlongGravityUp() {
        final Vector3d[] ups = {unit(1, 0, 0), unit(0, 1, 0), unit(0, 0, 1)};

        for (Vector3d up : ups) {
            // Put the feet 0.05 below the floor top along the gravity axis:
            // floor top is 1.0, so feet = 0.95 and the OBB centre = 1.85.
            final Vector3d position = new Vector3d(5.0, 5.0, 5.0);
            if (Math.abs(up.x) > 0.99) {
                position.x = 0.95;
            } else if (Math.abs(up.y) > 0.99) {
                position.y = 0.95;
            } else {
                position.z = 0.95;
            }
            final Aabb floor = floorBelow(up);
            final OrientedBox box = PlayerObb.box(position.x, position.y, position.z,
                    WIDTH, HEIGHT, up);

            final Vector3d push = PlayerObb.penetration(box, floor);
            assertNotNull(push, "expected contact for up " + up);
            assertEquals(0.05, push.length(), 1.0e-6,
                    "penetration should be the structural overlap for up " + up);
            assertTrue(push.dot(up) / push.length() > 0.99,
                    "terrain below gravity must push along gravity up: " + push);

            final OrientedBox separated = new OrientedBox(
                    box.center().add(push), box.halfExtents(), box.rotation());
            assertNull(PlayerObb.penetration(separated, floor),
                    "the corrected box must be free for up " + up);
        }
    }

    @Test
    void supportNormalWinsOverAShallowerSidewaysContact() {
        final Vector3d up = unit(1, 0, 0);
        final Vector3d shallowWall = new Vector3d(0.0, 0.08, 0.0);
        final Vector3d support = new Vector3d(0.30, 0.0, 0.0);

        final Vector3d selected = PlayerObbCollision.selectPush(
                List.of(shallowWall, support), up, true, 0.6);

        assertNotNull(selected);
        assertEquals(0.30, selected.x, EPS);
        assertEquals(0.0, selected.y, EPS);
    }

    @Test
    void supportPreferenceDoesNotTakeAnUnboundedPush() {
        final Vector3d up = unit(0, 1, 0);
        final Vector3d shallowWall = new Vector3d(0.05, 0.0, 0.0);
        final Vector3d deepFloor = new Vector3d(0.0, 1.2, 0.0);

        final Vector3d selected = PlayerObbCollision.selectPush(
                List.of(shallowWall, deepFloor), up, true, 0.6);

        assertNotNull(selected);
        assertEquals(0.05, selected.x, EPS,
                "a floor deeper than the support budget stays a normal push");
    }

    @Test
    void repeatedTerrainContactKeepsTheSameExitNormal() {
        final Vector3d up = unit(1, 0, 0);
        final Aabb floor = floorBelow(up);
        Vector3d center = new Vector3d(1.88, 0.0, 0.0);

        for (int tick = 0; tick < 8; tick++) {
            final OrientedBox box = new OrientedBox(center, new Vector3d(0.3, 0.9, 0.3),
                    new org.joml.Quaterniond().rotationTo(0.0, 1.0, 0.0, up.x, up.y, up.z));
            final Vector3d push = PlayerObb.penetration(box, floor);
            assertNotNull(push, "tick " + tick + " should still contact the floor");
            assertTrue(new Vector3d(push).normalize().dot(up) > 0.99,
                    "exit normal must not flip between ticks: " + push);

            // Separate, then let gravity pull the body 2 cm back into the floor.
            center = center.add(push);
            center.fma(-0.02, up);
        }
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual, String message) {
        assertEquals(expected.x, actual.x, EPS, message + " (x)");
        assertEquals(expected.y, actual.y, EPS, message + " (y)");
        assertEquals(expected.z, actual.z, EPS, message + " (z)");
    }

    /**
     * Terrain that is "below" for the given gravity up direction. The floor
     * top lies at 1.0 along the gravity axis.
     */
    private static Aabb floorBelow(Vector3d up) {
        if (Math.abs(up.x) > 0.99) {
            final double sign = up.x > 0.0 ? 1.0 : -1.0;
            return sign > 0.0
                    ? new Aabb(new Vector3d(0.0, -8.0, -8.0), new Vector3d(1.0, 8.0, 8.0))
                    : new Aabb(new Vector3d(-1.0, -8.0, -8.0), new Vector3d(0.0, 8.0, 8.0));
        }
        if (Math.abs(up.y) > 0.99) {
            final double sign = up.y > 0.0 ? 1.0 : -1.0;
            return sign > 0.0
                    ? new Aabb(new Vector3d(-8.0, 0.0, -8.0), new Vector3d(8.0, 1.0, 8.0))
                    : new Aabb(new Vector3d(-8.0, -1.0, -8.0), new Vector3d(8.0, 0.0, 8.0));
        }
        final double sign = up.z > 0.0 ? 1.0 : -1.0;
        return sign > 0.0
                ? new Aabb(new Vector3d(-8.0, -8.0, 0.0), new Vector3d(8.0, 8.0, 1.0))
                : new Aabb(new Vector3d(-8.0, -8.0, -1.0), new Vector3d(8.0, 8.0, 0.0));
    }
}
