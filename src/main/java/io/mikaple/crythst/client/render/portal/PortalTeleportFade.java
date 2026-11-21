package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalCrossing;
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
 * Very short, subtle client-side transition around a portal traversal.
 *
 * <p>The portal view is intended to be geometrically continuous: traversal is
 * triggered when the eye crosses the plane and the player is placed so the new
 * camera position equals the transformed eye. The black fade is therefore only
 * a brief visual settle (two ticks, low alpha), never a curtain that hides a
 * snap. The fallback jump detection still covers command/respawn teleports
 * where there is no portal crossing at all.</p>
 *
 * <p>Uses only client events and a HUD fill; no resource reload or network
 * packet is involved.</p>
 */
public final class PortalTeleportFade {
    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_teleport_fade");
    /** Total fade duration in client ticks. */
    private static final int FADE_TICKS = 2;
    /** Do not retrigger immediately after a traversal. */
    private static final int TRIGGER_COOLDOWN_TICKS = 10;
    /** Fallback position jump: 5 blocks squared. */
    private static final double JUMP_THRESHOLD_SQUARED = 25.0;
    private static final float MAX_ALPHA = 0.18f;

    private static int fadeTicks;
    private static int cooldownTicks;
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
            // No previous sample means there is no crossing to detect yet.
            rememberPosition(player);
            return;
        }
        if (player.isSpectator()) {
            rememberPosition(player);
            return;
        }

        if (!player.isPassenger() && cooldownTicks == 0) {
            if (crossedPortalPlane(player, minecraft)) {
                trigger();
            } else {
                final double dx = player.getX() - lastX;
                final double dy = player.getY() - lastY;
                final double dz = player.getZ() - lastZ;
                if (dx * dx + dy * dy + dz * dz > JUMP_THRESHOLD_SQUARED) {
                    trigger();
                }
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

    private static boolean crossedPortalPlane(LocalPlayer player, Minecraft minecraft) {
        final Vec3 eye = player.getEyePosition();
        final Vector3d current = new Vector3d(eye.x, eye.y, eye.z);
        final Vector3d previous = new Vector3d(
                player.xo, player.yo + player.getEyeHeight(), player.zo);
        for (PortalSnapshot snapshot : PortalClientCache.get()) {
            if (!snapshot.dimension().equals(minecraft.level.dimension())) {
                continue;
            }
            if (PortalCrossing.crossesFrontToBack(snapshot.plane(), previous, current)) {
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
        hasLastPosition = false;
    }
}
