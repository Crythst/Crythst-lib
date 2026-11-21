package io.mikaple.crythst.api.client;

import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureSelectionIgnore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Objects;

/**
 * Public clip-context extension for client structure selection.
 *
 * <p>Vanilla {@code ClientLevel.clip} is already extended internally to merge
 * structure hits with real blocks. This class exposes the two operations a
 * compatibility layer usually needs:</p>
 *
 * <ul>
 *   <li>run the same merged clip from a mixin or a Create/Sable adapter, and</li>
 *   <li>temporarily ignore one structure or all structures without mutating
 *       the world, the level or the physics body.</li>
 * </ul>
 *
 * <p>Ignores are thread-local and nest. Real blocks always keep precedence: if
 * a vanilla block is closer, the clip returns the vanilla hit even when a
 * structure is visible.</p>
 *
 * <p>This type is client-only and must not be loaded on a dedicated server.</p>
 */
public final class StructureClipExtension {
    private StructureClipExtension() {
    }

    /**
     * Runs the merged vanilla + structure clip with the current client views.
     */
    public static BlockHitResult clip(ClientLevel level, ClipContext context) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(context, "context");
        return StructureSelection.clip(level, context, ClientStructureSelection.views());
    }

    /**
     * Ignores one structure for the current thread until the handle is closed.
     */
    public static AutoCloseable ignore(long bodyId) {
        return StructureSelectionIgnore.ignore(bodyId);
    }

    /**
     * Ignores every structure for the current thread until the handle is
     * closed. The main level's real blocks are still returned normally.
     */
    public static AutoCloseable ignoreAll() {
        return StructureSelectionIgnore.ignoreAll();
    }

    /** Whether the current thread ignores the given structure. */
    public static boolean isIgnored(long bodyId) {
        return StructureSelectionIgnore.isIgnored(bodyId);
    }

    /** Whether the current thread ignores every structure. */
    public static boolean ignoresAll() {
        return StructureSelectionIgnore.ignoresAll();
    }

    /** Drops all ignores for the current thread. */
    public static void clear() {
        StructureSelectionIgnore.clear();
    }
}
