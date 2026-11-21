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
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
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
 * pitch and stores it in {@link CameraRenderState#orientation}. Its view
 * direction is {@code R * (0,0,-1)} and its reference up is
 * {@code R * (0,1,0)}. With directional gravity we rebuild the state from the
 * camera's own vanilla rotation and left-multiply by {@code Q}, the quaternion
 * that maps world up onto the player's gravity up:</p>
 *
 * <pre>
 * orientation' = Q * R
 * forward'     = Q * vanillaLook
 * local up'    = Q * worldUp
 * </pre>
 *
 * <p>Starting from {@code Camera#rotation()} rather than from
 * {@code state#orientation} is important: portal capture and other nested
 * extraction paths can call {@code extractRenderState} again on a state that
 * already contains a gravity-rotated quaternion. Reading the authoritative
 * vanilla value makes the injection idempotent and never applies {@code Q}
 * twice.</p>
 *
 * <p>The third-person camera position is computed by vanilla in the world-up
 * frame. Its offset from the player's eye is rotated by the same {@code Q}, so
 * the camera stays behind the rotated view. First-person offsets are zero and
 * the vanilla position is written back untouched.</p>
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
        final Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        // Compose from the camera's vanilla rotation, never from whatever the
        // render state currently holds. Nested extraction therefore cannot
        // stack Q on top of a previous gravity rotation.
        final Quaternionf vanillaOrientation = new Quaternionf(
                ((Camera) (Object) this).rotation());
        state.orientation.set(new Quaternionf(gravity).mul(vanillaOrientation));

        // Rotate the third-person offset from the eye into the gravity frame.
        // In first person the offset is zero, so leave the vanilla position and
        // block position exactly as extracted.
        final Vec3 vanillaPosition = ((Camera) (Object) this).position();
        final Vec3 eyePosition = player.getEyePosition(state.cameraEntityPartialTicks);
        final Vector3d eye = new Vector3d(eyePosition.x, eyePosition.y, eyePosition.z);
        final Vector3d vanilla = new Vector3d(
                vanillaPosition.x, vanillaPosition.y, vanillaPosition.z);
        final Vector3d rotatedPosition = state.isFirstPerson
                ? vanilla
                : PlayerGravity.rotateOffset(
                        new Quaterniond(gravity.x, gravity.y, gravity.z, gravity.w),
                        eye, vanilla);
        final Vector3d offset = new Vector3d(rotatedPosition).sub(eye);
        if (!state.isFirstPerson) {
            state.pos = new Vec3(rotatedPosition.x, rotatedPosition.y, rotatedPosition.z);
        } else {
            state.pos = vanillaPosition;
        }
        state.blockPos = BlockPos.containing(state.pos);

        // The direction the rotated camera shows, and the gravity up. These are
        // also what looks/raycasts must use (PlayerGravity.lookDirection).
        final Vector3f forward = state.orientation.transform(new Vector3f(0.0f, 0.0f, -1.0f));
        final Vector3f up = gravity.transform(new Vector3f(0.0f, 1.0f, 0.0f));
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

        crythst$logGravityCamera(gravity, forward, up, offset);
    }

    @Unique
    private static long crythst$lastGravityLogNanos;

    @Unique
    private static void crythst$logGravityCamera(Quaternionf gravity, Vector3f forward,
                                                 Vector3f up, Vector3d posOffset) {
        final long now = System.nanoTime();
        if (now - crythst$lastGravityLogNanos < 1_000_000_000L) {
            return;
        }
        crythst$lastGravityLogNanos = now;
        Crythst.LOGGER.info(
                "Crythst gravity camera: q=({}, {}, {}, {}) up=({}, {}, {}) "
                        + "forward=({}, {}, {}) posOffset=({}, {}, {})",
                fmt(gravity.x), fmt(gravity.y), fmt(gravity.z), fmt(gravity.w),
                fmt(up.x), fmt(up.y), fmt(up.z),
                fmt(forward.x), fmt(forward.y), fmt(forward.z),
                fmt(posOffset.x), fmt(posOffset.y), fmt(posOffset.z));
    }

    @Unique
    private static String fmt(float value) {
        return String.format("%.3f", value);
    }

    @Unique
    private static String fmt(double value) {
        return String.format("%.3f", value);
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
