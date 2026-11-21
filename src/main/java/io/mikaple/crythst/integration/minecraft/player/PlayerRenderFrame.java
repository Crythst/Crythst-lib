package io.mikaple.crythst.integration.minecraft.player;

import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Shared <em>feet frame</em> for a player under directional gravity.
 *
 * <p>The collision OBB ({@link PlayerObb}), the rendered avatar and the camera
 * all use {@code entity.position()} as the frame origin. The gravity rotation
 * {@code Q} maps world {@code +Y} onto the player's {@code up} direction, so a
 * point {@code p} in that frame is placed at
 * {@code position + Q * p}.</p>
 *
 * <p>This class is deliberately pure JOML. It contains no client or entity
 * access, so the composition can be unit tested and the client mixins can share
 * exactly the same arithmetic.</p>
 */
public final class PlayerRenderFrame {
    /** Vanilla adds this world-Y offset above the name-tag attachment. */
    public static final double NAME_TAG_EXTRA_HEIGHT = 0.5;

    private PlayerRenderFrame() {
    }

    /**
     * Rotates {@code point} around {@code anchor} by {@code rotation}.
     */
    public static Vector3d rotateAboutAnchor(Quaterniondc rotation, Vector3dc anchor,
                                             Vector3dc point) {
        return new Vector3d(point).sub(anchor).rotate(rotation).add(anchor);
    }

    /**
     * The eye position for a player whose feet are at {@code feet}:
     * {@code feet + Q * (0, eyeHeight, 0)}.
     *
     * <p>This is the anchor from which vanilla derives the third-person camera
     * offset. Rotating the whole vanilla camera position around the feet is
     * equivalent to using this eye plus a rotated offset.</p>
     */
    public static Vector3d eye(Vector3dc feet, Quaterniondc rotation, double eyeHeight) {
        return rotateAboutAnchor(rotation, feet,
                new Vector3d(feet).add(0.0, eyeHeight, 0.0));
    }

    /**
     * Name-tag attachment that puts the submitted tag above the rotated head.
     *
     * <p>{@code SubmitNodeCollection.submitNameTag} translates by
     * {@code attachment + (0, 0.5, 0)} in world space. The model is rotated
     * about the feet, so that final world offset must be
     * {@code Q * (attachment + (0, 0.5, 0))}. Returning
     * {@code Q * (attachment + (0, 0.5, 0)) - (0, 0.5, 0)} cancels the
     * unrotated vanilla addition.</p>
     */
    public static Vector3d nameTagAttachment(Quaterniondc rotation, Vector3dc attachment) {
        return new Vector3d(attachment)
                .add(0.0, NAME_TAG_EXTRA_HEIGHT, 0.0)
                .rotate(rotation)
                .sub(0.0, NAME_TAG_EXTRA_HEIGHT, 0.0);
    }

    /**
     * The avatar pose up to the head of {@code setupRotations}:
     * {@code T(feet) * S(scale) * Q}.
     *
     * <p>The pose stack inside {@code LivingEntityRenderer} then appends
     * vanilla's body yaw, the {@code (-1, -1, 1)} model flip and the
     * {@code -1.501} model translation. Because all of those are right of
     * {@code Q}, they stay in the feet frame and the fixed point of {@code Q}
     * is exactly {@code feet}.</p>
     */
    public static Matrix4f avatarTransform(double feetX, double feetY, double feetZ,
                                           double scale, Quaterniondc rotation) {
        return new Matrix4f()
                .translation((float) feetX, (float) feetY, (float) feetZ)
                .scale((float) scale)
                .rotate(toFloat(rotation));
    }

    /**
     * Converts the double-precision frame quaternion to the float form used by
     * the pose stack and camera render state.
     */
    public static Quaternionf toFloat(Quaterniondc rotation) {
        if (rotation == null) {
            return new Quaternionf();
        }
        return new Quaternionf(
                (float) rotation.x(), (float) rotation.y(),
                (float) rotation.z(), (float) rotation.w());
    }
}
