package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The exact world projection used by the main pass, including view bob, hurt
 * tilt and the portal/nausea spin.
 *
 * <p>26.3 builds this matrix inside {@code GameRenderer.renderLevel} as a
 * local, just before {@code RenderSystem.setProjectionMatrix}. The portal
 * capture runs earlier during extraction, so it must reproduce the same
 * transform or the captured view stretches/offsets relative to the quad's
 * screen-space UVs.</p>
 */
final class PortalProjection {
    private static final Vector3f SPIN_AXIS = new Vector3f(
            0.0f, Mth.SQRT_OF_TWO / 2.0f, Mth.SQRT_OF_TWO / 2.0f);

    private PortalProjection() {
    }

    static Matrix4f worldProjection(CameraRenderState camera,
                                    PlayerRenderState player,
                                    OptionsRenderState options) {
        if (camera == null || camera.projectionMatrix == null || options == null) {
            return new Matrix4f();
        }
        final Matrix4f projection = new Matrix4f(camera.projectionMatrix);

        final PoseStack poseStack = new PoseStack();
        bobHurt(camera, options, poseStack);
        if (options.bobView) {
            bobView(camera, poseStack);
        }
        projection.mul(poseStack.last().pose());

        if (player != null) {
            final float screenEffectScale = options.screenEffectScale;
            final float spinning = Math.max(player.portalEffectIntensity,
                    player.nauseaEffectIntensity) * (screenEffectScale * screenEffectScale);
            if (spinning > 0.0f) {
                float skew = 5.0f / (spinning * spinning + 5.0f) - spinning * 0.04f;
                skew *= skew;
                final float angle = player.spinningEffectAngle * (float) (Math.PI / 180.0);
                projection.rotate(angle, SPIN_AXIS);
                projection.scale(1.0f / skew, 1.0f, 1.0f);
                projection.rotate(-angle, SPIN_AXIS);
            }
        }
        return projection;
    }

    private static void bobHurt(CameraRenderState camera, OptionsRenderState options,
                                PoseStack poseStack) {
        if (!camera.entityRenderState.isLiving) {
            return;
        }
        float hurt = camera.entityRenderState.hurtTime;
        if (camera.entityRenderState.isDeadOrDying) {
            final float duration = Math.min(camera.entityRenderState.deathTime, 20.0f);
            poseStack.rotateDegrees(Axis.ZP, 40.0f - 8000.0f / (duration + 200.0f));
        }
        if (hurt < 0.0f) {
            return;
        }
        hurt /= camera.entityRenderState.hurtDuration;
        hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
        final float hurtDir = camera.entityRenderState.hurtDir;
        poseStack.rotateDegrees(Axis.YP, -hurtDir);
        final float tilt = (float) (-hurt * 14.0 * options.damageTiltStrength);
        poseStack.rotateDegrees(Axis.ZP, tilt);
        poseStack.rotateDegrees(Axis.YP, hurtDir);
    }

    private static void bobView(CameraRenderState camera, PoseStack poseStack) {
        if (!camera.entityRenderState.isPlayer) {
            return;
        }
        final float walk = camera.entityRenderState.backwardsInterpolatedWalkDistance;
        final float bob = camera.entityRenderState.bob;
        poseStack.translate(
                Mth.sin(walk * (float) Math.PI) * bob * 0.5f,
                -Math.abs(Mth.cos(walk * (float) Math.PI) * bob),
                0.0f);
        poseStack.rotateDegrees(Axis.ZP, Mth.sin(walk * (float) Math.PI) * bob * 3.0f);
        poseStack.rotateDegrees(Axis.XP,
                Math.abs(Mth.cos(walk * (float) Math.PI - 0.2f) * bob) * 5.0f);
    }
}
