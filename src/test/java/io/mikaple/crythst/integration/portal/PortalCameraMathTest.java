package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.client.render.portal.PortalCameraMath;
import io.mikaple.crythst.client.render.portal.PortalClip;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure checks for the virtual destination camera and projective portal mapping.
 */
class PortalCameraMathTest {
    private static final double EPS = 1.0e-6;

    private static PortalPlane entry() {
        return new PortalPlane(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    private static PortalPlane exit() {
        return new PortalPlane(
                new Vector3d(20.0, 70.0, -5.0),
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0),
                2.0, 3.0);
    }

    private static Matrix4f projection() {
        return new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0), 16.0f / 9.0f, 1000.0f, 0.05f, true);
    }

    @Test
    void cameraPathAcrossThePlaneIsContinuous() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final Vector3d before = new Vector3d(0.3, 65.2, 0.25);
        final Vector3d after = new Vector3d(0.3, 65.2, -0.25);
        final Vector3d transformedBefore = transform.transformPoint(before);
        final Vector3d transformedAfter = transform.transformPoint(after);

        assertEquals(before.distance(after), transformedBefore.distance(transformedAfter), 1.0e-9,
                "the rigid transform preserves the camera's per-tick path length");
        assertEquals(0.0, exit().signedDistance(transform.transformPoint(
                new Vector3d(0.3, 65.2, 0.0))), 1.0e-9,
                "the crossing instant lands exactly on the exit plane");

        final Vector3d beforeVelocity = new Vector3d(0.0, 0.0, -0.5);
        final Vector3d afterVelocity = transform.transformDirection(beforeVelocity);
        assertEquals(beforeVelocity.length(), afterVelocity.length(), 1.0e-9,
                "speed is continuous across the seam");
        assertTrue(afterVelocity.dot(exit().normal()) > 0.0,
                "the transformed camera leaves through the exit front");
    }

    @Test
    void transformedCameraUsesRigidLinkPose() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final Vector3d mainPosition = new Vector3d(0.3, 65.2, 4.0);
        final Quaterniond mainOrientation = new Quaterniond().rotateY(0.4).rotateX(-0.2);
        final PortalCameraMath.Pose pose = PortalCameraMath.portalCamera(
                transform, mainPosition, mainOrientation, projection());

        assertVectorEquals(transform.transformPoint(mainPosition), pose.position());
        assertEquals(1.0, pose.orientation().dot(
                transform.transformRotation(mainOrientation)), 1.0e-9,
                "orientation must be the rigid transform of the main orientation");

        final Matrix4f expectedView = new Matrix4f().rotation(
                new org.joml.Quaternionf(pose.orientation()).conjugate());
        assertMatrixEquals(expectedView, pose.viewRotation());
    }

    @Test
    void portalPlaneSamplesMatchMainCameraScreenPosition() {
        final PortalTransform transform = PortalTransform.between(entry(), exit());
        final Vector3d mainPosition = new Vector3d(0.4, 65.5, 3.7);
        final Quaterniond mainOrientation = new Quaterniond().rotateY(0.2).rotateX(-0.1);
        final Matrix4f mainProjection = projection();
        final Matrix4f mainView = new Matrix4f().rotation(
                new org.joml.Quaternionf(mainOrientation).conjugate());
        final Matrix4f mainViewProjection = PortalCameraMath.mainViewProjection(
                mainProjection, mainView);

        final PortalCameraMath.Pose portal = PortalCameraMath.portalCamera(
                transform, mainPosition, mainOrientation, mainProjection);

        // Sample several points on the entry plane, including the border.
        final double[][] local = {
                {0.0, 0.0}, {0.7, -1.1}, {-0.9, 1.2}, {0.0, 1.5}};
        for (double[] point : local) {
            final Vector3d world = entry().worldPoint(new Vector3d(point[0], point[1], 0.0));
            final PortalCameraMath.Projected main = PortalCameraMath.project(
                    mainViewProjection, mainPosition, world);
            final PortalCameraMath.Projected destination = PortalCameraMath.project(
                    portal.viewProjection(), portal.position(), transform.transformPoint(world));

            assertEquals(main.ndcX(), destination.ndcX(), 1.0e-4,
                    "portal UV must equal the main-camera screen position");
            assertEquals(main.ndcY(), destination.ndcY(), 1.0e-4);
            assertEquals(main.w(), destination.w(), 1.0e-4);
        }
    }

    @Test
    void targetSizePreservesAspectAndCapsResolution() {
        final int[] full = PortalCameraMath.targetSize(1920, 1080, 1.0f, 1920, 1080);
        assertEquals(1920, full[0]);
        assertEquals(1080, full[1]);

        final int[] half = PortalCameraMath.targetSize(1920, 1080, 0.5f, 1920, 1080);
        assertEquals(960, half[0]);
        assertEquals(540, half[1]);

        final int[] capped = PortalCameraMath.targetSize(3840, 2160, 1.0f, 1920, 1080);
        assertTrue(capped[0] <= 1920);
        assertTrue(capped[1] <= 1080);
        final double expectedAspect = 3840.0 / 2160.0;
        assertEquals(expectedAspect, capped[0] / (double) capped[1], 0.01);
    }

    @Test
    void obliqueClipMapsPortalPlaneToNearDepth() {
        final Matrix4f projection = projection();
        final Matrix4f viewRotation = new Matrix4f(); // looking -Z
        final Vector3d camera = new Vector3d(0.0, 0.0, 5.0);
        final Vector3d origin = new Vector3d(0.0, 0.0, 0.0);
        final Vector3d normal = new Vector3d(0.0, 0.0, 1.0);
        final Matrix4f clipped = PortalClip.obliqueNearPlane(
                projection, viewRotation, camera, origin, normal,
                PortalClip.DepthConvention.ZERO_TO_ONE_REVERSED);

        final Vector4f onPlane = clipped.transform(new Vector4f(0.0f, 0.0f, -5.0f, 1.0f));
        assertEquals(1.0f, onPlane.z / onPlane.w, 1.0e-4,
                "exit plane must map exactly to the near depth");
        assertTrue(clipValid(onPlane), "the exit plane itself must not be clipped");

        final Vector4f beyond = clipped.transform(new Vector4f(0.0f, 0.0f, -15.0f, 1.0f));
        assertTrue(clipValid(beyond), "destination geometry beyond the exit is visible");
        assertTrue(beyond.z / beyond.w < 1.0f);

        final Vector4f cameraSide = clipped.transform(new Vector4f(0.0f, 0.0f, -1.0f, 1.0f));
        assertTrue(!clipValid(cameraSide), "geometry between camera and portal is clipped");
    }

    @Test
    void obliqueClipFlipsForCameraOnBackSide() {
        final Matrix4f projection = projection();
        // Camera at -Z looking +Z: orientation rotates 180 degrees about Y.
        final Quaterniond orientation = new Quaterniond().rotateY(Math.PI);
        final Matrix4f viewRotation = new Matrix4f().rotation(
                new org.joml.Quaternionf(orientation).conjugate());
        final Vector3d camera = new Vector3d(0.0, 0.0, -5.0);
        final Matrix4f clipped = PortalClip.obliqueNearPlane(
                projection, viewRotation, camera,
                new Vector3d(0.0, 0.0, 0.0), new Vector3d(0.0, 0.0, 1.0),
                PortalClip.DepthConvention.ZERO_TO_ONE_REVERSED);

        // World +Z is beyond the plane from this camera; a point between the
        // camera and the plane must be clipped.
        final org.joml.Vector3f beyondView = viewRotation.transformPosition(
                new org.joml.Vector3f(0.0f, 0.0f, 15.0f - (float) camera.z));
        final org.joml.Vector3f betweenView = viewRotation.transformPosition(
                new org.joml.Vector3f(0.0f, 0.0f, -1.0f - (float) camera.z));
        final Vector4f beyond = clipped.transform(
                new Vector4f(beyondView.x, beyondView.y, beyondView.z, 1.0f));
        final Vector4f behind = clipped.transform(
                new Vector4f(betweenView.x, betweenView.y, betweenView.z, 1.0f));
        assertTrue(clipValid(beyond));
        assertTrue(!clipValid(behind));
    }

    private static boolean clipValid(Vector4f clip) {
        return clip.z >= 0.0f && clip.z <= clip.w + 1.0e-5f;
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(column, row), actual.get(column, row), 1.0e-5,
                        "matrix[" + column + "][" + row + "]");
            }
        }
    }
}
