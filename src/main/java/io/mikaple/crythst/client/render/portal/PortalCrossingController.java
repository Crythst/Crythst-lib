package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalCrossing;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.integration.portal.PortalTeleportMath;
import io.mikaple.crythst.integration.portal.PortalTransform;
import io.mikaple.crythst.network.portal.PortalTraversalPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side continuous crossing prediction.
 *
 * <p>When the local player's body centre sweeps through a linked portal, the
 * client applies the same rigid transform the server will apply: position,
 * velocity, yaw/pitch and old-position interpolation state. The camera therefore
 * continues through the opening instead of waiting for the server's end-of-tick
 * teleport. The server remains authoritative; its teleport/correction packet
 * overwrites this prediction if the two disagree.</p>
 *
 * <p>Cross-dimension links are deliberately not predicted here: a client has
 * only one loaded {@code ClientLevel} and cannot move itself into another
 * dimension. Those crossings wait for the server transition.</p>
 */
public final class PortalCrossingController {
    /** Matches the server teleporter so both sides suppress an immediate return. */
    public static final int COOLDOWN_TICKS = 10;

    private static final Map<UUID, Integer> READY_TICK = new HashMap<>();
    private static Prediction prediction;
    private static boolean registered;

    private record Prediction(LocalPlayer player, int tick, PortalTransform transform,
                              boolean positive, PositionMoveRotation target, boolean acknowledged) {
    }

    private PortalCrossingController() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(PortalCrossingController::tick);
    }

    private static void tick(Minecraft minecraft) {
        final LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || player.isPassenger()
                || player.isSpectator() || player.isRemoved()) {
            if (player == null) {
                READY_TICK.clear();
                prediction = null;
            }
            return;
        }
        final int tick = (int) minecraft.level.getGameTime();
        if (prediction != null && (prediction.player() != player
                || player.tickCount - prediction.tick() > 4)) {
            prediction = null;
        }
        READY_TICK.entrySet().removeIf(entry -> entry.getValue() <= tick);

        final Vec3 centre = player.getBoundingBox().getCenter();
        final Vector3d current = new Vector3d(centre.x, centre.y, centre.z);
        final Vector3d previous = new Vector3d(
                centre.x - (player.getX() - player.xo),
                centre.y - (player.getY() - player.yo),
                centre.z - (player.getZ() - player.zo));

        final List<PortalSnapshot> snapshots = PortalClientCache.effective(System.nanoTime());
        for (PortalSnapshot portal : snapshots) {
            if (!portal.dimension().equals(minecraft.level.dimension())
                    || !portal.linked()
                    || !portal.dimension().equals(portal.destinationDimension())) {
                continue;
            }
            if (portal.destinationPlane() == null) {
                continue;
            }
            final Integer ready = READY_TICK.get(portal.id());
            if (ready != null && tick < ready) {
                continue;
            }
            final PortalCrossing.Crossing crossing =
                    PortalCrossing.findCrossing(portal.shape(), previous, current);
            if (crossing == null || !crossing.inside()) {
                continue;
            }
            apply(player, portal, previous, current, crossing, tick);
            break;
        }
    }

    private static void apply(LocalPlayer player, PortalSnapshot portal,
                              Vector3d previous, Vector3d current, PortalCrossing.Crossing crossing, int tick) {
        final PortalTransform transform =
                PortalTransform.between(portal.plane(), portal.destinationPlane());
        final Vec3 eye = player.getEyePosition();
        final Vector3d eyeVector = new Vector3d(eye.x, eye.y, eye.z);
        final Vec3 delta = player.getDeltaMovement();
        final Vector3d sourceVelocity = new Vector3d(delta.x, delta.y, delta.z);
        final float sourceYaw = player.getYRot();
        final float sourcePitch = player.getXRot();
        final Vector3d feet = PortalTeleportMath.playerFeetAfterCrossing(
                transform, eyeVector, player.getEyeHeight(), current.y - player.getY(),
                crossing.alongPositiveDirection());
        final Vector3d velocity = transform.transformDirection(sourceVelocity);
        final float[] rotation = PortalTransform.yawPitch(transform.transformDirection(
                PortalTransform.viewDirection(sourceYaw, sourcePitch)));
        final float[] previousRotation = PortalTransform.yawPitch(transform.transformDirection(
                PortalTransform.viewDirection(player.yRotO, player.xRotO)));
        final Vector3d previousFeet = PortalTeleportMath.previousFeetAfterCrossing(
                transform, feet, new Vector3d(current).sub(previous));

        final double fallDistance = player.fallDistance;
        final boolean onGround = player.onGround();
        player.setPos(feet.x, feet.y, feet.z);
        player.setYRot(rotation[0]);
        player.setXRot(rotation[1]);
        player.setDeltaMovement(velocity.x, velocity.y, velocity.z);
        player.setOldPosAndRot(toVec(previousFeet),
                rotation[0] + Mth.wrapDegrees(previousRotation[0] - rotation[0]), previousRotation[1]);
        player.fallDistance = fallDistance;
        player.setOnGround(onGround);
        prediction = new Prediction(player, player.tickCount, transform,
                crossing.alongPositiveDirection(), PositionMoveRotation.of(player), false);

        READY_TICK.put(portal.id(), tick + COOLDOWN_TICKS);
        if (portal.linkedPortalId() != null) {
            READY_TICK.put(portal.linkedPortalId(), tick + COOLDOWN_TICKS);
        }
        try {
            ClientPlayNetworking.send(new PortalTraversalPayload(
                    portal.id(), previous, current, eyeVector, sourceVelocity,
                    sourceYaw, sourcePitch));
        } catch (Throwable ignored) {
            // The server's tick pass still detects the crossing if the intent
            // packet cannot be sent (for example during disconnect).
        }
    }

    /** Maps the camera back to the entry while this tick still interpolates before the crossing. */
    public static PortalTransform entryCameraTransform(LocalPlayer player, Vec3 cameraPosition) {
        final Prediction current = prediction;
        if (current == null || current.player() != player || current.tick() != player.tickCount
                || PortalTeleportMath.eyeReachedExit(current.transform().exit(),
                        new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z), current.positive())) {
            return null;
        }
        return current.transform().inverse();
    }

    /** Matches only the nearby authoritative confirmation of a local portal prediction. */
    public static boolean acknowledge(LocalPlayer player, PositionMoveRotation target) {
        final Prediction current = prediction;
        if (current == null || current.player() != player || current.acknowledged()
                || player.tickCount - current.tick() > 4) {
            return false;
        }
        final PositionMoveRotation predicted = current.target();
        if (target.position().distanceToSqr(predicted.position()) > 0.35 * 0.35
                || Math.abs(Mth.wrapDegrees(target.yRot() - predicted.yRot())) > 10.0
                || Math.abs(target.xRot() - predicted.xRot()) > 10.0) {
            prediction = null;
            return false;
        }
        prediction = new Prediction(player, current.tick(), current.transform(),
                current.positive(), predicted, true);
        return true;
    }

    private static Vec3 toVec(Vector3d value) {
        return new Vec3(value.x, value.y, value.z);
    }
}
