package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Installs the structure voxel under the crosshair as Minecraft's real
 * {@link Minecraft#hitResult}.
 *
 * <p>Vanilla's outline extractor, crosshair and use-item/attack paths all read
 * {@code Minecraft.hitResult}. Computing the structure ray separately in each
 * of those places could show one voxel while acting on another. This class
 * replaces the pick result once per frame with a synthetic
 * {@link BlockHitResult} when a structure is closer than the vanilla hit, so
 * every consumer sees the same target. A real block (or entity) that is closer
 * always wins and no synthetic result is installed.</p>
 *
 * <p>Only the client uses this. The server never receives the synthetic state;
 * it re-raycasts from the player's eye and look vector.</p>
 */
public final class ClientStructureTargeting {
    /**
     * Real blocks win ties. The epsilon only absorbs floating-point noise from
     * two independent raycasts.
     */
    private static final double REAL_HIT_EPSILON = 1.0e-3;

    private static volatile HitResult lastSynthetic;
    private static volatile ClientStructureRay.Hit activeHit;

    private ClientStructureTargeting() {
    }

    /**
     * Called at the tail of {@code Minecraft.pick(float)} after vanilla has
     * written its own pick result.
     */
    public static void afterPick(Minecraft minecraft) {
        final LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null
                || !CrythstFabric.config().structureBlockInteractionEnabled) {
            clear(minecraft);
            return;
        }

        final HitResult vanilla = minecraft.hitResult;
        if (vanilla instanceof EntityHitResult || minecraft.crosshairPickEntity != null) {
            // An entity under the crosshair is a real target and must win.
            clear(minecraft);
            return;
        }

        final ClientStructureRay.Hit structure = ClientStructureRay.raycast(
                player, player.blockInteractionRange());
        if (structure == null) {
            clear(minecraft);
            return;
        }

        final double vanillaDistance = distance(player.getEyePosition(), vanilla);
        if (structure.distance() + REAL_HIT_EPSILON >= vanillaDistance) {
            // The real block is in front (or at the same surface).
            clear(minecraft);
            return;
        }

        final BlockHitResult synthetic = structure.clickResult();
        minecraft.hitResult = synthetic;
        lastSynthetic = synthetic;
        activeHit = structure;
    }

    /**
     * The structure target shared by the outline and interaction code.
     *
     * @return the active hit only while it is the same object Minecraft uses as
     *         its current hit result, otherwise {@code null}
     */
    public static ClientStructureRay.Hit activeHit(Minecraft minecraft) {
        return minecraft != null && minecraft.hitResult == lastSynthetic ? activeHit : null;
    }

    /**
     * @return whether the current vanilla hit result was installed by this
     *         class
     */
    public static boolean isSynthetic(Minecraft minecraft) {
        return minecraft != null && minecraft.hitResult == lastSynthetic;
    }

    private static void clear(Minecraft minecraft) {
        // Minecraft.pick already wrote the real pick result before this hook
        // runs; dropping the marker is enough to make it authoritative again.
        lastSynthetic = null;
        activeHit = null;
    }

    private static double distance(Vec3 eye, HitResult hit) {
        return hit == null || hit.getType() != HitResult.Type.BLOCK || hit.getLocation() == null
                ? Double.POSITIVE_INFINITY
                : eye.distanceTo(hit.getLocation());
    }
}
