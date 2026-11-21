package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.portal.PortalTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rotates the extracted camera into the player's gravity frame.
 *
 * <p>Vanilla builds the camera orientation {@code R} from the player's yaw and
 * pitch and stores it in {@link CameraRenderState#orientation}. Its local up is
 * world up. When the player has directional gravity we left-multiply by the
 * quaternion {@code Q} that maps world up to the player's up, so the camera's
 * whole basis is rotated into the gravity frame:</p>
 *
 * <pre>orientation' = Q * orientation</pre>
 *
 * <p>The view matrix is rebuilt exactly the way
 * {@code Camera.getViewRotationMatrix} does it, i.e. from the conjugate of the
 * orientation. The culling frustum is rebuilt from the rotated view matrix so
 * that terrain is not culled against the old, rotated-away view.</p>
 *
 * <p>When {@code Q} is the identity (no override, or gravity pointing straight
 * down) the injection returns before touching the render state. This keeps the
 * default experience bit-for-bit identical to vanilla.</p>
 */
@Mixin(Camera.class)
public abstract class CameraGravityMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void crythst$rotateIntoGravityFrame(CameraRenderState state,
                                                DeltaTracker deltaTracker,
                                                CallbackInfo ci) {
        final Quaternionf gravity = crythst$gravityRotation();
        if (gravity == null) {
            return;
        }

        // Left-multiply in world space: the whole camera basis, including its
        // local up, is carried into the player's gravity frame.
        state.orientation.set(new Quaternionf(gravity).mul(state.orientation));
        crythst$logGravityCamera(gravity, state);

        // Third-person offset is computed by vanilla in the world-up frame.
        // Rotate the offset from the eye into the gravity frame; first-person
        // offsets are ~zero and therefore unchanged. State-only mutation keeps
        // repeated extractor calls (portal captures) from applying Q twice.
        final Player player = Minecraft.getInstance().player;
        if (player != null && state.pos != null) {
            final Vec3 eye = player.getEyePosition(state.cameraEntityPartialTicks);
            final Vector3d offset = new Vector3d(
                    state.pos.x - eye.x, state.pos.y - eye.y, state.pos.z - eye.z);
            offset.rotate(new Quaterniond(gravity.x, gravity.y, gravity.z, gravity.w));
            state.pos = new Vec3(eye.x + offset.x, eye.y + offset.y, eye.z + offset.z);
            state.blockPos = BlockPos.containing(state.pos);
        }

        // Update the yaw/pitch exposed to entity/particle extraction so they
        // match the rotated view rather than the vanilla world-up frame.
        final Vector3f forward = state.orientation.transform(new Vector3f(0.0f, 0.0f, -1.0f));
        final float[] yawPitch = PortalTransform.yawPitch(
                new Vector3d(forward.x, forward.y, forward.z));
        state.yRot = yawPitch[0];
        state.xRot = yawPitch[1];

        // Mirror Camera.getViewRotationMatrix(): rotation matrix of the
        // conjugate orientation.
        final Quaternionf conjugate = state.orientation.conjugate(new Quaternionf());
        state.viewRotationMatrix.rotation(conjugate);

        // Rebuild the culling frustum against the rotated view and position.
        if (state.cullFrustum != null) {
            final Frustum frustum = new Frustum(state.viewRotationMatrix, state.projectionMatrix);
            frustum.prepare(state.pos.x, state.pos.y, state.pos.z);
            state.cullFrustum.set(frustum);
        }
    }

    @Unique
    private static long crythst$lastGravityLogNanos;

    @Unique
    private static void crythst$logGravityCamera(Quaternionf gravity, CameraRenderState state) {
        final long now = System.nanoTime();
        if (now - crythst$lastGravityLogNanos < 1_000_000_000L) {
            return;
        }
        crythst$lastGravityLogNanos = now;
        final Vector3f up = state.orientation.transform(new Vector3f(0.0f, 1.0f, 0.0f));
        Crythst.LOGGER.info("Crythst gravity camera: q=({}, {}, {}, {}) up=({}, {}, {})",
                String.format("%.3f", gravity.x), String.format("%.3f", gravity.y),
                String.format("%.3f", gravity.z), String.format("%.3f", gravity.w),
                String.format("%.3f", up.x), String.format("%.3f", up.y),
                String.format("%.3f", up.z));
    }

    /**
     * Sound and particles query the camera basis through these methods; keep
     * them consistent with the rendered gravity frame.
     */
    @Inject(method = "forwardVector", at = @At("RETURN"), cancellable = true)
    private void crythst$forwardVector(CallbackInfoReturnable<Vector3fc> cir) {
        final Quaternionf gravity = crythst$gravityRotation();
        if (gravity != null) {
            cir.setReturnValue(new Vector3f(cir.getReturnValue()).rotate(gravity));
        }
    }

    @Inject(method = "upVector", at = @At("RETURN"), cancellable = true)
    private void crythst$upVector(CallbackInfoReturnable<Vector3fc> cir) {
        final Quaternionf gravity = crythst$gravityRotation();
        if (gravity != null) {
            cir.setReturnValue(new Vector3f(cir.getReturnValue()).rotate(gravity));
        }
    }

    @Inject(method = "leftVector", at = @At("RETURN"), cancellable = true)
    private void crythst$leftVector(CallbackInfoReturnable<Vector3fc> cir) {
        final Quaternionf gravity = crythst$gravityRotation();
        if (gravity != null) {
            cir.setReturnValue(new Vector3f(cir.getReturnValue()).rotate(gravity));
        }
    }

    /**
     * @return the world-space gravity rotation, or {@code null} when the
     *         vanilla camera orientation must be left untouched
     */
    @Unique
    private Quaternionf crythst$gravityRotation() {
        final Player player = Minecraft.getInstance().player;
        if (player == null || !CrythstFabric.config().playerCameraGravity) {
            return null;
        }

        // The render state may be extracted for a detached camera (spectator,
        // panorama, ...); only rotate it for the local player's own camera.
        final Entity cameraEntity = ((Camera) (Object) this).entity();
        if (cameraEntity != null && cameraEntity != player) {
            return null;
        }

        if (!PlayerGravity.hasDirectionalGravity(player)) {
            return null;
        }
        final Quaterniond rotation = PlayerGravity.viewRotation(player);
        return new Quaternionf((float) rotation.x, (float) rotation.y,
                (float) rotation.z, (float) rotation.w);
    }
}
