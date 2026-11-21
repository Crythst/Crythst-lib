package io.mikaple.crythst.integration.minecraft.player;

import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.math.OrientedBox;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the feet frame shared by the avatar transform, the camera eye
 * and the player OBB.
 *
 * <p>No Minecraft client or live player is required. The avatar composition
 * mirrors the real render order from {@code LivingEntityRenderer.submit}:
 * {@code T(feet) * S(scale) * Q * R_bodyYaw * S(-1,-1,1) * S(0.9375) *
 * T(0,-1.501,0)}.</p>
 */
class PlayerRenderFrameTest {
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    private static final double EYE_HEIGHT = 1.62;
    private static final double EPS = 1.0e-6;

    private static final Vector3d FEET = new Vector3d(3.0, 64.0, -7.0);

    private static Vector3d unit(double x, double y, double z) {
        return new Vector3d(x, y, z).normalize();
    }

    private static Vector3d[] gravityUps() {
        return new Vector3d[]{
                unit(0, 1, 0),
                unit(1, 0, 0),
                unit(0, 0, 1),
                unit(-1, 0, 0),
                unit(0, 0, -1),
                unit(0.6, 0.8, 0.0),
                unit(-0.3, 0.4, 0.8660254037844386)
        };
    }

    @Test
    void avatarFixedPointIsTheFeetAnchor() {
        for (Vector3d up : gravityUps()) {
            final Quaterniond q = PlayerGravity.rotationForUp(up);
            final Matrix4f pose = PlayerRenderFrame.avatarTransform(
                    FEET.x, FEET.y, FEET.z, 1.0, q);

            final Vector3f fixedF = pose.transformPosition(new Vector3f(0.0f, 0.0f, 0.0f));
            final Vector3d fixed = new Vector3d(fixedF);
            assertVectorEquals(FEET, fixed,
                    "Q must leave the setupRotations origin at the feet for up " + up);

            final Vector3f modelUpF = pose.transformDirection(new Vector3f(0.0f, 1.0f, 0.0f));
            final Vector3d modelUp = new Vector3d(modelUpF).normalize();
            assertVectorEquals(up, modelUp,
                    "the avatar body +Y must follow gravity up for " + up);
        }
    }

    @Test
    void fullModelTransformKeepsFeetAndHeadOnTheObbAxis() {
        for (Vector3d up : gravityUps()) {
            final Quaterniond q = PlayerGravity.rotationForUp(up);

            // Real render order after setupRotations HEAD.
            final Matrix4f pose = PlayerRenderFrame.avatarTransform(
                    FEET.x, FEET.y, FEET.z, 1.0, q)
                    .rotateY((float) Math.toRadians(37.0))
                    .scale(-1.0f, -1.0f, 1.0f)
                    .scale(0.9375f, 0.9375f, 0.9375f)
                    .translate(0.0f, -1.501f, 0.0f);

            // Player model: 24 px feet, -8 px head top, 1 px = 1/16 block.
            final Vector3d modelFeet = new Vector3d(pose.transformPosition(
                    new Vector3f(0.0f, 24.0f / 16.0f, 0.0f)));
            final Vector3d modelHead = new Vector3d(pose.transformPosition(
                    new Vector3f(0.0f, -8.0f / 16.0f, 0.0f)));

            final Vector3d feetOffset = new Vector3d(modelFeet).sub(FEET);
            final Vector3d bodyAxis = new Vector3d(modelHead).sub(modelFeet).normalize();
            assertVectorEquals(up, bodyAxis,
                    "the rendered body axis must coincide with the OBB local +Y for " + up);

            // The model feet are one 0.9375-scaled model pixel above the feet
            // anchor (the vanilla -1.501 translation), so allow that tiny gap.
            assertTrue(feetOffset.length() < 2.0e-3,
                    "model feet must stay on the feet anchor for " + up + ": " + feetOffset);
            assertEquals(0.0, feetOffset.dot(up) - feetOffset.length(), 1.0e-6,
                    "model feet must be displaced only along gravity up for " + up);

            final OrientedBox obb = PlayerObb.box(
                    FEET.x, FEET.y, FEET.z, WIDTH, HEIGHT, up);
            final Vector3d obbFeet = new Vector3d(obb.center())
                    .fma(-HEIGHT * 0.5, obb.axes()[1]);
            assertVectorEquals(FEET, obbFeet,
                    "the OBB bottom must stay at the feet anchor for " + up);
            assertEquals(0.0, modelFeet.distance(obbFeet), 2.0e-3,
                    "the model feet must sit at the OBB bottom for " + up);
        }
    }

    @Test
    void cameraEyeUsesTheFeetAnchor() {
        for (Vector3d up : gravityUps()) {
            final Quaterniond q = PlayerGravity.rotationForUp(up);
            final Vector3d eye = PlayerRenderFrame.eye(FEET, q, EYE_HEIGHT);

            final Vector3d expected = new Vector3d(FEET)
                    .add(q.transform(new Vector3d(0.0, EYE_HEIGHT, 0.0)));
            assertVectorEquals(expected, eye,
                    "eye must be position + Q * eyeHeight for up " + up);

            // First person: the vanilla camera position is the vanilla eye.
            final Vector3d vanillaEye = new Vector3d(FEET).add(0.0, EYE_HEIGHT, 0.0);
            final Vector3d firstPerson = PlayerRenderFrame.rotateAboutAnchor(q, FEET, vanillaEye);
            assertVectorEquals(eye, firstPerson,
                    "first-person camera must use the rotated gravity eye for " + up);

            // Third person: eye + Q * offset, not an unrotated offset.
            final Vector3d vanillaOffset = new Vector3d(0.0, 0.2, -3.5);
            final Vector3d vanillaCamera = new Vector3d(vanillaEye).add(vanillaOffset);
            final Vector3d thirdPerson = PlayerRenderFrame.rotateAboutAnchor(
                    q, FEET, vanillaCamera);
            final Vector3d expectedThirdPerson = new Vector3d(eye)
                    .add(q.transform(vanillaOffset));
            assertVectorEquals(expectedThirdPerson, thirdPerson,
                    "third-person camera must be eye + Q * offset for " + up);
            assertEquals(vanillaOffset.length(),
                    new Vector3d(thirdPerson).sub(eye).length(), EPS,
                    "camera distance must be preserved for " + up);
        }
    }

    @Test
    void nameTagAnchorStaysAboveTheRotatedHead() {
        for (Vector3d up : gravityUps()) {
            final Quaterniond q = PlayerGravity.rotationForUp(up);
            final Vector3d vanillaAttachment = new Vector3d(0.0, HEIGHT, 0.0);
            final Vector3d corrected = PlayerRenderFrame.nameTagAttachment(
                    q, vanillaAttachment);

            // submitNameTag always adds (0, 0.5, 0) after the attachment.
            final Vector3d worldAnchor = new Vector3d(corrected)
                    .add(0.0, PlayerRenderFrame.NAME_TAG_EXTRA_HEIGHT, 0.0);
            final Vector3d expected = q.transform(new Vector3d(
                    0.0, HEIGHT + PlayerRenderFrame.NAME_TAG_EXTRA_HEIGHT, 0.0));
            assertVectorEquals(expected, worldAnchor,
                    "the name tag must stay above the rotated head for " + up);

            final Vector3d feetToAnchor = new Vector3d(worldAnchor).add(FEET);
            assertVectorEquals(new Vector3d(FEET).fma(
                            HEIGHT + PlayerRenderFrame.NAME_TAG_EXTRA_HEIGHT, up),
                    feetToAnchor,
                    "the name tag must follow the gravity up axis from the feet for " + up);
        }
    }

    @Test
    void identityUpLeavesEveryAnchorInVanillaSpace() {
        final Quaterniond q = PlayerGravity.rotationForUp(unit(0, 1, 0));
        final Vector3d eye = PlayerRenderFrame.eye(FEET, q, EYE_HEIGHT);
        assertVectorEquals(new Vector3d(FEET).add(0.0, EYE_HEIGHT, 0.0), eye,
                "vanilla up must keep the vertical eye");

        final Vector3d attachment = new Vector3d(0.0, HEIGHT, 0.0);
        assertVectorEquals(attachment,
                PlayerRenderFrame.nameTagAttachment(q, attachment),
                "vanilla up must not move the name-tag attachment");
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual, String message) {
        assertEquals(expected.x, actual.x, EPS, message + " (x)");
        assertEquals(expected.y, actual.y, EPS, message + " (y)");
        assertEquals(expected.z, actual.z, EPS, message + " (z)");
    }
}
