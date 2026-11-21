package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.client.render.portal.PortalCameraSanity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the portal-capture render-thread freeze.
 *
 * <p>Vanilla's frustum offset loop is unbounded; these tests ensure the pure
 * validation gate rejects exactly the degenerate inputs that used to keep that
 * loop alive forever.</p>
 */
class PortalCameraSanityTest {
    private static final double X = 12.0;
    private static final double Y = 64.0;
    private static final double Z = -7.0;

    @Test
    void acceptsNormalPerspectiveCamera() {
        assertTrue(PortalCameraSanity.isUsable(
                projection(), viewRotation(), orientation(), X, Y, Z));
    }

    @Test
    void rejectsDegenerateProjection() {
        assertFalse(PortalCameraSanity.isSanePerspective(new Matrix4f()));
        assertFalse(PortalCameraSanity.isSanePerspective(new Matrix4f().zero()));
        assertFalse(PortalCameraSanity.isSanePerspective(new Matrix4f().ortho(
                -10.0f, 10.0f, -10.0f, 10.0f, 0.05f, 1000.0f)));
        assertFalse(PortalCameraSanity.isSanePerspective(projection().m00(Float.NaN)));
        assertFalse(PortalCameraSanity.isSanePerspective(projection().m11(Float.POSITIVE_INFINITY)));
        // A vanishingly small FOV makes 1/tan(fov/2) explode.
        assertFalse(PortalCameraSanity.isSanePerspective(new Matrix4f().perspective(
                (float) Math.toRadians(1.0e-7), 16.0f / 9.0f, 0.05f, 1000.0f)));
        // A near-180-degree FOV makes 1/tan(fov/2) approach zero.
        assertFalse(PortalCameraSanity.isSanePerspective(new Matrix4f().perspective(
                (float) Math.toRadians(179.99), 16.0f / 9.0f, 0.05f, 1000.0f)));
    }

    @Test
    void rejectsDegenerateOrientationAndRotation() {
        assertFalse(PortalCameraSanity.isUsable(
                projection(), viewRotation(), new Quaternionf(0.0f, 0.0f, 0.0f, 0.0f), X, Y, Z));
        assertFalse(PortalCameraSanity.isUsable(
                projection(), viewRotation(), new Quaternionf(Float.NaN, 0.0f, 0.0f, 1.0f), X, Y, Z));
        assertFalse(PortalCameraSanity.isUsable(
                projection(), viewRotation(), new Quaternionf(0.0f, 0.0f, 0.0f, Float.POSITIVE_INFINITY), X, Y, Z));
        assertFalse(PortalCameraSanity.isSaneRotation(new Matrix4f().zero()));
        assertFalse(PortalCameraSanity.isSaneRotation(viewRotation().scale(0.0f)));
        assertFalse(PortalCameraSanity.isSaneRotation(viewRotation().m22(Float.NaN)));
    }

    @Test
    void rejectsNonFinitePosition() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 1.0e30}) {
            assertFalse(PortalCameraSanity.isUsable(
                    projection(), viewRotation(), orientation(), bad, Y, Z));
            assertFalse(PortalCameraSanity.isUsable(
                    projection(), viewRotation(), orientation(), X, bad, Z));
            assertFalse(PortalCameraSanity.isUsable(
                    projection(), viewRotation(), orientation(), X, Y, bad));
        }
    }

    @Test
    void rejectsDegenerateCameraQuickly() {
        final Matrix4f zeroProjection = new Matrix4f().zero();
        final Matrix4f nanProjection = projection().m00(Float.NaN);
        final Quaternionf nanOrientation = new Quaternionf(Float.NaN, 0.0f, 0.0f, 1.0f);
        // If a future change reintroduces an unbounded vanilla call here this
        // loop would starve the test thread instead of finishing promptly.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 100_000; i++) {
                assertFalse(PortalCameraSanity.isUsable(
                        zeroProjection, viewRotation(), orientation(), X, Y, Z));
                assertFalse(PortalCameraSanity.isUsable(
                        nanProjection, viewRotation(), orientation(), X, Y, Z));
                assertFalse(PortalCameraSanity.isUsable(
                        projection(), viewRotation(), nanOrientation, X, Y, Z));
                assertFalse(PortalCameraSanity.isUsable(
                        projection(), viewRotation(), orientation(), Double.NaN, Y, Z));
            }
        });
    }

    private static Matrix4f projection() {
        return new Matrix4f().perspective(
                (float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
    }

    private static Matrix4f viewRotation() {
        return new Matrix4f().rotation(new Quaternionf().rotateY(0.3f).rotateX(-0.15f));
    }

    private static Quaternionf orientation() {
        return new Quaternionf().rotateY(0.3f).rotateX(-0.15f);
    }
}
