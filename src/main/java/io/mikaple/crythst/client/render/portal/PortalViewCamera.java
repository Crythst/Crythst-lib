package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.integration.portal.PortalTransform;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The virtual destination camera and the exact projections used for one frame.
 *
 * <p>{@link #projection()} is the destination render projection with the exit
 * portal plane as its oblique near plane. {@link #mainViewProjection()} is the
 * unmodified main-camera screen projection stored with the texture; the portal
 * quad samples the texture at its own main-camera screen position, which is
 * mathematically identical to projecting {@code T(q)} through the destination
 * camera.</p>
 */
record PortalViewCamera(Vec3 position, Vec3 mainCapturePosition,
                        Quaternionf orientation, Matrix4f viewRotation,
                        Matrix4f projection, Matrix4f mainViewProjection,
                        Frustum frustum, float yaw, float pitch,
                        PortalTransform transform) {

    record Result(PortalViewCamera camera, String failureReason) {
        static Result failure(String reason) {
            return new Result(null, reason);
        }
    }

    static Result build(PortalSnapshot portal, CameraRenderState main,
                        LevelRenderState levelState, OptionsRenderState options) {
        return build(portal, main, levelState, options, null);
    }

    /**
     * Variant for nested portals: {@code baseProjection} is the parent portal
     * camera's already-composed projection (including its exit clip). Reusing
     * it avoids applying view bob/hurt a second time.
     */
    static Result build(PortalSnapshot portal, CameraRenderState main,
                        LevelRenderState levelState, OptionsRenderState options,
                        Matrix4f baseProjection) {
        if (main == null || main.pos == null || main.projectionMatrix == null
                || main.orientation == null) {
            return Result.failure("missing main camera matrix");
        }
        if (portal.destinationPlane() == null) {
            return Result.failure("portal has no destination plane");
        }

        final PortalTransform transform = PortalTransform.between(
                portal.plane(), portal.destinationPlane());
        final Matrix4f mainProjection = baseProjection != null
                ? new Matrix4f(baseProjection)
                : PortalProjection.worldProjection(
                        main, levelState.playerRenderState, options);
        final PortalCameraMath.Pose camera = PortalCameraMath.portalCamera(
                transform,
                new Vector3d(main.pos.x, main.pos.y, main.pos.z),
                new org.joml.Quaterniond(main.orientation),
                mainProjection);
        final Vector3d position = camera.position();
        if (!PortalCameraSanity.isFinitePosition(position.x, position.y, position.z)) {
            return Result.failure("non-finite camera position");
        }

        final Quaternionf orientation = new Quaternionf(camera.orientation());
        if (!PortalCameraSanity.isSaneOrientation(orientation)) {
            return Result.failure("non-finite orientation");
        }
        orientation.normalize();

        final Matrix4f viewRotation = camera.viewRotation();
        final Matrix4f projection = PortalClip.obliqueNearPlane(
                mainProjection, viewRotation,
                position,
                portal.destinationPlane().origin(),
                portal.destinationPlane().normal(),
                PortalClip.DepthConvention.ZERO_TO_ONE_REVERSED);
        if (!PortalCameraSanity.isUsable(projection, viewRotation, orientation,
                position.x, position.y, position.z)) {
            return Result.failure("degenerate projection or view rotation");
        }

        final Vec3 portalPos = new Vec3(position.x, position.y, position.z);
        final Vector3f forward = orientation.transform(new Vector3f(0.0f, 0.0f, -1.0f));
        final float[] yawPitch = PortalTransform.yawPitch(
                new Vector3d(forward.x, forward.y, forward.z));
        final Frustum frustum = new Frustum(viewRotation, projection);
        frustum.prepare(position.x, position.y, position.z);

        final Matrix4f mainViewProjection = PortalCameraMath.mainViewProjection(
                mainProjection, main.viewRotationMatrix);

        return new Result(new PortalViewCamera(portalPos, main.pos, orientation,
                viewRotation, projection, mainViewProjection, frustum,
                yawPitch[0], yawPitch[1], transform), null);
    }
}
