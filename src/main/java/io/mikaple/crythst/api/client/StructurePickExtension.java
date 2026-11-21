package io.mikaple.crythst.api.client;

import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only access to the structure hit that client picking produced.
 *
 * <p>Vanilla picking and outline extraction already receive a
 * {@link StructureBlockHitResult} when a structure voxel is targeted. This
 * class lets compatibility code query that hit without rerunning a differently
 * quantised raycast. Both accessors observe the ignore set installed through
 * {@link StructureClipExtension}.</p>
 *
 * <p>This type is client-only and must not be loaded on a dedicated server.</p>
 */
public final class StructurePickExtension {
    private StructurePickExtension() {
    }

    /**
     * The structure hit currently stored in {@code Minecraft.hitResult}, or
     * {@code null} when the player is aiming at a real block or nothing.
     */
    public static @Nullable StructureBlockHitResult current() {
        return current(Minecraft.getInstance());
    }

    /** Explicit-client overload for tests and render helpers. */
    public static @Nullable StructureBlockHitResult current(@Nullable Minecraft client) {
        return ClientStructureSelection.current(client);
    }

    /**
     * A structure-only raycast from the local player's eye along the
     * gravity-adjusted look vector. The normal picking path should prefer
     * {@link #current()}; this method is for diagnostics and adapters.
     */
    public static @Nullable StructureBlockHitResult raycast(double reach) {
        final Minecraft client = Minecraft.getInstance();
        return client == null ? null : raycast(client.player, reach);
    }

    /** Explicit-player overload. */
    public static @Nullable StructureBlockHitResult raycast(@Nullable LocalPlayer player,
                                                            double reach) {
        return ClientStructureSelection.raycast(player, reach);
    }
}
