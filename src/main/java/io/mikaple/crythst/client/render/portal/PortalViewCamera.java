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
 * The virtual destination camera and the exact projection used by the main
 * pass for this frame.
 */
record PortalViewCamera(Vec3 position, Quaternionf orientation, Matrix4f viewRotation,
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
        if (main == null || main.pos == null || main.projectionMatrix == null
                || main.orientation == null) {
            return Result.failure("missing main camera matrix");
        }

        final PortalTransform transform = PortalTransform.between(
                portal.plane(), portal.destinationPlane());
        final Vector3d position = transform.transformPoint(
                new Vector3d(main.pos.x, main.pos.y, main.pos.z));
        if (!PortalCameraSanity.isFinitePosition(position.x, position.y, position.z)) {
            return Result.failure("non-finite camera position");
        }

        final Quaternionf orientation = new Quaternionf(main.orientation)
                .premul(new Quaternionf(transform.rotation()));
        if (!PortalCameraSanity.isSaneOrientation(orientation)) {
            return Result.failure("non-finite orientation");
        }
        orientation.normalize();

        final Matrix4f viewRotation = new Matrix4f().rotation(
                new Quaternionf(orientation).conjugate());
        final Matrix4f projection = PortalProjection.worldProjection(
                main, levelState.playerRenderState, options);
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
        // The same bobbed projection with the MAIN camera view: the captured
        // image stays fixed to the portal plane between refreshes instead of
        // sliding as the player moves.
        final Matrix4f mainViewProjection = new Matrix4f(projection)
                .mul(main.viewRotationMatrix);

        return new Result(new PortalViewCamera(portalPos, orientation, viewRotation,
                projection, mainViewProjection, frustum, yawPitch[0], yawPitch[1],
                transform), null);
    }
}
