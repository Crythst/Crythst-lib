package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureSelection;
import io.mikaple.crythst.integration.minecraft.selection.StructureSelection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds a {@code clip(ClipContext)} override to {@link ClientLevel}.
 *
 * <p>{@code Level} inherits the default {@code BlockGetter.clip}. A concrete
 * method on {@code ClientLevel} overrides that default only for client worlds,
 * which is exactly where vanilla picking, outline extraction and interaction
 * rays run. It is intentionally not an interface overwrite: the integrated
 * server must keep the plain vanilla clip for its authoritative occlusion
 * checks.</p>
 *
 * <p>The method is an ordinary added method (not {@code @Unique}), so the JVM
 * dispatches virtual calls such as {@code Level.clip} to it.</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelClipMixin {
    public BlockHitResult clip(ClipContext context) {
        final ClientLevel level = (ClientLevel) (Object) this;
        if (!StructureSelection.enabled()) {
            return StructureSelection.vanillaClip(level, context);
        }
        return StructureSelection.clip(level, context, ClientStructureSelection.views());
    }
}
