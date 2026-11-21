package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Short client-side black fade around a portal traversal.
 *
 * <p>The server teleport is necessarily instantaneous: position, velocity and
 * view rotation are applied in one packet. A short full-screen fade hides the
 * seam without moving the camera or interfering with input/movement.</p>
 *
 * <p>Triggers when the local player's bounding-box centre enters a known portal
 * trigger volume, or as a fallback when the client position jumps more than a
 * few blocks in one tick (commands, respawns, cross-dimension). It uses only
 * client events and a HUD fill; no resource reload or network packet is
 * involved.</p>
 */
public final class PortalTeleportFade {
    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_teleport_fade");
    /** Total fade duration in client ticks. */
    private static final int FADE_TICKS = 10;
    /** Do not retrigger while the player is still standing in the trigger. */
    private static final int TRIGGER_COOLDOWN_TICKS = 20;
    /** Fallback position jump: 5 blocks squared. */
    private static final double JUMP_THRESHOLD_SQUARED = 25.0;
    private static final float MAX_ALPHA = 0.72f;

    private static int fadeTicks;
    private static int cooldownTicks;
    private static boolean wasInsidePortal;
    private static boolean hasLastPosition;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static boolean registered;

    private PortalTeleportFade() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(PortalTeleportFade::tick);
        HudElementRegistry.addLast(ELEMENT_ID, PortalTeleportFade::extract);
    }

    private static void tick(Minecraft minecraft) {
        if (fadeTicks > 0) {
            fadeTicks--;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        final LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            return;
        }

        if (!hasLastPosition) {
            // Do not flash merely because the player joined while standing in
            // a portal volume.
            wasInsidePortal = isInsidePortal(player, minecraft);
            rememberPosition(player);
            return;
        }
        if (player.isSpectator()) {
            wasInsidePortal = false;
            rememberPosition(player);
            return;
        }

        final boolean inside = isInsidePortal(player, minecraft);
        if (inside && !wasInsidePortal && cooldownTicks == 0 && !player.isPassenger()) {
            trigger();
        }
        wasInsidePortal = inside;

        if (!player.isPassenger() && cooldownTicks == 0) {
            final double dx = player.getX() - lastX;
            final double dy = player.getY() - lastY;
            final double dz = player.getZ() - lastZ;
            if (dx * dx + dy * dy + dz * dz > JUMP_THRESHOLD_SQUARED) {
                trigger();
            }
        }
        rememberPosition(player);
    }

    private static void rememberPosition(LocalPlayer player) {
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        hasLastPosition = true;
    }

    private static boolean isInsidePortal(LocalPlayer player, Minecraft minecraft) {
        final Vec3 centre = player.getBoundingBox().getCenter();
        final Vector3d point = new Vector3d(centre.x, centre.y, centre.z);
        for (PortalSnapshot snapshot : PortalClientCache.get()) {
            if (!snapshot.dimension().equals(minecraft.level.dimension())) {
                continue;
            }
            if (snapshot.plane().contains(point, 0.35)) {
                return true;
            }
        }
        return false;
    }

    private static void trigger() {
        fadeTicks = FADE_TICKS;
        cooldownTicks = TRIGGER_COOLDOWN_TICKS;
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (fadeTicks <= 0) {
            return;
        }
        final float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        final float factor = Math.max(0.0f, Math.min(1.0f,
                (fadeTicks - partialTick) / (float) FADE_TICKS));
        final int alpha = (int) (MAX_ALPHA * factor * 255.0f);
        if (alpha <= 0) {
            return;
        }
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
    }

    private static void reset() {
        fadeTicks = 0;
        cooldownTicks = 0;
        wasInsidePortal = false;
        hasLastPosition = false;
    }
}
