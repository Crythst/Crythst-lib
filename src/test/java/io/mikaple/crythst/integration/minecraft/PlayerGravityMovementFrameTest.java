package io.mikaple.crythst.integration.minecraft;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for mapping vanilla {@code (xxa, yya, zza)} input into a
 * directional-gravity frame. No client, world or live player is required.
 */
class PlayerGravityMovementFrameTest {
    private static final double EPS = 1.0e-9;
    private static final Vector3d WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    @Test
    void worldUpHorizontalLookUsesVanillaAxes() {
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, 1.0), WORLD_UP, new Vector3d(0.0, 0.0, 1.0));

        // Looking south (+Z), the camera's right is west (-X).
        assertVectorEquals(new Vector3d(0.0, 0.0, 1.0), frame.forward());
        assertVectorEquals(new Vector3d(-1.0, 0.0, 0.0), frame.right());
        assertVectorEquals(WORLD_UP, frame.up());

        // Forward input stays on the look direction.
        assertVectorEquals(new Vector3d(0.0, 0.0, 0.2),
                move(frame, 0.0, 1.0, 0.2));

        // Vanilla xxa is positive for strafe-left, i.e. away from "right".
        assertVectorEquals(new Vector3d(0.2, 0.0, 0.0),
                move(frame, 1.0, 0.0, 0.2));
        assertVectorEquals(new Vector3d(-0.2, 0.0, 0.0),
                move(frame, -1.0, 0.0, 0.2));
    }

    @Test
    void gravityUpAlongPositiveXBuildsRightHandedFrame() {
        final Vector3d up = new Vector3d(1.0, 0.0, 0.0);
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, 1.0), up, new Vector3d(0.0, 0.0, 1.0));

        assertVectorEquals(new Vector3d(0.0, 0.0, 1.0), frame.forward());
        assertVectorEquals(new Vector3d(0.0, 1.0, 0.0), frame.right());
        assertVectorEquals(new Vector3d(0.0, 0.0, 0.1), move(frame, 0.0, 1.0, 0.1));
        assertVectorEquals(new Vector3d(0.0, -0.1, 0.0), move(frame, 1.0, 0.0, 0.1));
    }

    @Test
    void gravityUpAlongPositiveZBuildsRightHandedFrame() {
        final Vector3d up = new Vector3d(0.0, 0.0, 1.0);
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(1.0, 0.0, 0.0), up, new Vector3d(1.0, 0.0, 0.0));

        assertVectorEquals(new Vector3d(1.0, 0.0, 0.0), frame.forward());
        assertVectorEquals(new Vector3d(0.0, -1.0, 0.0), frame.right());
        assertVectorEquals(new Vector3d(0.1, 0.0, 0.0), move(frame, 0.0, 1.0, 0.1));
        assertVectorEquals(new Vector3d(0.0, 0.1, 0.0), move(frame, 1.0, 0.0, 0.1));
    }

    @Test
    void gravityUpAlongAllWorldAxesStaysOrthonormal() {
        final Vector3d[] ups = {
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 1.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0)
        };
        for (Vector3d up : ups) {
            final PlayerGravity.MovementFrame frame = frame(
                    new Vector3d(0.0, 0.6, 0.8), up, new Vector3d(1.0, 0.0, 0.0));
            assertUnit(frame.forward());
            assertUnit(frame.right());
            assertUnit(frame.up());
            assertEquals(0.0, frame.forward().dot(frame.up()), EPS,
                    "forward must be orthogonal to up for up=" + up);
            assertEquals(0.0, frame.forward().dot(frame.right()), EPS,
                    "forward must be orthogonal to right for up=" + up);
            assertEquals(0.0, frame.right().dot(frame.up()), EPS,
                    "right must be orthogonal to up for up=" + up);
            assertVectorEquals(new Vector3d(frame.forward()).cross(frame.up()),
                    frame.right(), "right must be forward x up for up=" + up);
        }
    }

    @Test
    void lookIsProjectedOntoTheGravityPlane() {
        final Vector3d up = WORLD_UP;
        final Vector3d look = new Vector3d(0.5, 0.9, 0.3);
        final PlayerGravity.MovementFrame frame =
                frame(look, up, new Vector3d(0.0, 0.0, 1.0));

        final Vector3d horizontal = new Vector3d(0.5, 0.0, 0.3).normalize();
        assertVectorEquals(horizontal, frame.forward());
        assertEquals(0.0, frame.forward().dot(up), EPS,
                "look's component along gravity up must be removed");
        assertUnit(frame.forward());
    }

    @Test
    void degenerateLookFallsBackToVanillaYawForward() {
        // Gravity up is north (-Z). Looking straight along up has no forward
        // component in the gravity plane, so the horizontal yaw fallback wins.
        final Vector3d up = new Vector3d(0.0, 0.0, -1.0);
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, -1.0), up, new Vector3d(1.0, 0.0, 0.0));

        assertVectorEquals(new Vector3d(1.0, 0.0, 0.0), frame.forward(),
                "parallel look must use the projected fallback forward");
        assertVectorEquals(new Vector3d(0.0, 1.0, 0.0), frame.right());
    }

    @Test
    void zeroLookFallsBackToVanillaYawForward() {
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, 0.0), WORLD_UP, new Vector3d(0.0, 0.0, 1.0));

        assertVectorEquals(new Vector3d(0.0, 0.0, 1.0), frame.forward());
        assertVectorEquals(new Vector3d(-1.0, 0.0, 0.0), frame.right());
    }

    @Test
    void fullyDegenerateDirectionsStillProduceAUsableFrame() {
        final Vector3d up = new Vector3d(0.0, 0.0, -1.0);
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, -1.0), up, new Vector3d(0.0, 0.0, 1.0));

        assertFinite(frame.forward());
        assertFinite(frame.right());
        assertUnit(frame.forward());
        assertUnit(frame.right());
        assertEquals(0.0, frame.forward().dot(up), EPS,
                "the stable fallback must be perpendicular to up");
        assertTrue(frame.forward().lengthSquared() > 0.5,
                "the stable fallback must not collapse to zero");
    }

    @Test
    void inputIsNormalizedAndScaledLikeVanilla() {
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, 1.0), WORLD_UP, new Vector3d(0.0, 0.0, 1.0));

        // Vanilla normalizes lengths > 1 before applying the speed.
        assertVectorEquals(new Vector3d(0.06, 0.0, 0.08),
                move(frame, 3.0, 4.0, 0.1));
        // A diagonal input has length sqrt(2) and must be normalized too.
        assertVectorEquals(new Vector3d(0.1414213562373095, 0.0, 0.1414213562373095),
                move(frame, 1.0, 1.0, 0.2));
        // Short input is only scaled.
        assertVectorEquals(new Vector3d(0.05, 0.0, 0.0),
                move(frame, 0.5, 0.0, 0.1));
    }

    @Test
    void zeroAndTinyInputProduceNoMovement() {
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, 1.0), WORLD_UP, new Vector3d(0.0, 0.0, 1.0));

        assertVectorEquals(new Vector3d(), move(frame, 0.0, 0.0, 0.2));
        // Vanilla's getInputVector ignores lengthSqr < 1e-7.
        assertVectorEquals(new Vector3d(),
                PlayerGravity.movementInGravityFrame(
                        new Vector3d(1.0e-4, 0.0, 0.0), 0.2, frame));
    }

    @Test
    void verticalInputComponentIsLeftOnWorldY() {
        final PlayerGravity.MovementFrame frame = frame(
                new Vector3d(0.0, 0.0, 1.0), WORLD_UP, new Vector3d(0.0, 0.0, 1.0));

        final Vector3d movement = PlayerGravity.movementInGravityFrame(
                new Vector3d(0.0, 0.5, 0.0), 0.2, frame);
        assertVectorEquals(new Vector3d(0.0, 0.1, 0.0), movement,
                "the Y input component must not be rotated into the frame");
    }

    private static PlayerGravity.MovementFrame frame(Vector3d look, Vector3d up,
                                                     Vector3d fallback) {
        return PlayerGravity.movementFrame(look, up, fallback);
    }

    private static Vector3d move(PlayerGravity.MovementFrame frame, double inputX,
                                 double inputZ, double speed) {
        return PlayerGravity.movementInGravityFrame(
                new Vector3d(inputX, 0.0, inputZ), speed, frame);
    }

    private static void assertUnit(Vector3d vector) {
        assertFinite(vector);
        assertEquals(1.0, vector.length(), 1.0e-9, "vector must be unit length");
    }

    private static void assertFinite(Vector3d vector) {
        assertFalse(Double.isNaN(vector.x) || Double.isInfinite(vector.x), "x must be finite");
        assertFalse(Double.isNaN(vector.y) || Double.isInfinite(vector.y), "y must be finite");
        assertFalse(Double.isNaN(vector.z) || Double.isInfinite(vector.z), "z must be finite");
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual) {
        assertVectorEquals(expected, actual, "");
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual,
                                           String message) {
        assertEquals(expected.x, actual.x, EPS, message + " (x)");
        assertEquals(expected.y, actual.y, EPS, message + " (y)");
        assertEquals(expected.z, actual.z, EPS, message + " (z)");
    }
}
