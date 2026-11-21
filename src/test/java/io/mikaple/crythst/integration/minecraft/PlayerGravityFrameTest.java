package io.mikaple.crythst.integration.minecraft;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the gravity-frame quaternion {@code Q} and the third-person
 * camera offset rotation. No Minecraft client or live player is required.
 */
class PlayerGravityFrameTest {
    private static final Vector3d WORLD_UP = new Vector3d(0.0, 1.0, 0.0);
    private static final double EPS = 1.0e-9;

    @Test
    void identityUpProducesIdentityRotation() {
        final Quaterniond q = PlayerGravity.rotationForUp(WORLD_UP);
        assertEquals(0.0, q.x, EPS);
        assertEquals(0.0, q.y, EPS);
        assertEquals(0.0, q.z, EPS);
        assertEquals(1.0, q.w, EPS);
    }

    @Test
    void rotationMapsWorldUpOntoGravityUp() {
        final Vector3d[] ups = {
                new Vector3d(0.0, 0.0, -1.0),   // looking south
                new Vector3d(1.0, 0.0, 0.0),    // looking west
                new Vector3d(0.0, 0.0, 1.0),    // looking north
                new Vector3d(-1.0, 0.0, 0.0),   // looking east
                new Vector3d(0.0, 1.0, 0.0),    // looking straight down
                new Vector3d(0.0, -1.0, 0.0),   // looking straight up
                new Vector3d(0.6, 0.8, 0.0).normalize(),
                new Vector3d(-0.3, 0.4, 0.8660254037844386).normalize()
        };
        for (Vector3d up : ups) {
            final Quaterniond q = PlayerGravity.rotationForUp(up);
            assertVectorEquals(up, q.transform(new Vector3d(WORLD_UP)),
                    "Q * worldUp must equal up for " + up);
        }
    }

    @Test
    void lookModeRotationComposesWithLookDirection() {
        // A horizontal look is sampled as "down", so gravity up is its
        // opposite (north when looking south).
        final Vector3d look = new Vector3d(0.0, 0.0, 1.0);
        final Vector3d up = new Vector3d(look).negate();
        final Quaterniond q = PlayerGravity.rotationForUp(up);

        // forward = Q * vanillaLook. This is the direction the camera shows
        // and the direction interaction raycasts must use.
        final Vector3d forward = q.transform(new Vector3d(look));
        assertVectorEquals(new Vector3d(0.0, 1.0, 0.0), forward,
                "horizontal south look rotates to world up in its own gravity frame");

        // The old bug was a feedback loop: Q was rebuilt from the live look, so
        // Q * look stayed world up for every yaw and yaw only rolled the camera.
        // With Q frozen at enable time, a different yaw must produce a
        // different forward.
        final Vector3d eastLook = new Vector3d(1.0, 0.0, 0.0);
        final Vector3d eastForward = q.transform(new Vector3d(eastLook));
        assertVectorEquals(new Vector3d(1.0, 0.0, 0.0), eastForward,
                "yawing while Q is frozen must rotate the view");
        assertTrue(forward.distance(eastForward) > 0.5,
                "forward must not be invariant under yaw");
    }

    @Test
    void thirdPersonOffsetRotatesAsARigidVector() {
        final Vector3d up = new Vector3d(0.0, 0.0, -1.0);
        final Quaterniond q = PlayerGravity.rotationForUp(up);
        final Vector3d eye = new Vector3d(12.0, 64.0, -7.0);
        final Vector3d vanillaCamera = new Vector3d(12.0, 64.0, -11.0);
        final Vector3d offset = new Vector3d(vanillaCamera).sub(eye);

        final Vector3d rotated = PlayerGravity.rotateOffset(q, eye, vanillaCamera);

        assertEquals(offset.length(), new Vector3d(rotated).sub(eye).length(), 1.0e-9,
                "rigid rotation preserves the camera distance");
        assertVectorEquals(new Vector3d(eye).add(q.transform(new Vector3d(offset))),
                rotated, "camera position must be eye + Q * offset");

        // Rotating back with the conjugate restores the vanilla position.
        final Vector3d restored = PlayerGravity.rotateOffset(q.conjugate(new Quaterniond()),
                eye, rotated);
        assertVectorEquals(vanillaCamera, restored, "offset rotation must round-trip");
    }

    @Test
    void firstPersonZeroOffsetIsUnchanged() {
        final Quaterniond q = PlayerGravity.rotationForUp(new Vector3d(0.0, 0.0, -1.0));
        final Vector3d eye = new Vector3d(3.0, 70.0, 5.0);
        final Vector3d rotated = PlayerGravity.rotateOffset(q, eye, new Vector3d(eye));
        assertVectorEquals(eye, rotated, "a zero camera offset must stay at the eye");
    }

    @Test
    void gravityUpStaysUnitUnderRotation() {
        final Vector3d up = new Vector3d(0.4, 0.5, -0.7681145747868608).normalize();
        final Quaterniond q = PlayerGravity.rotationForUp(up);
        final Vector3d rotatedUp = q.transform(new Vector3d(WORLD_UP));
        assertEquals(1.0, rotatedUp.length(), 1.0e-9);
        assertVectorEquals(up, rotatedUp, "local up after Q is the gravity up");
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual, String message) {
        assertEquals(expected.x, actual.x, EPS, message + " (x)");
        assertEquals(expected.y, actual.y, EPS, message + " (y)");
        assertEquals(expected.z, actual.z, EPS, message + " (z)");
    }
}
