package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureInteraction;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects middle-click pick-block for physics structure voxels.
 *
 * <p>Vanilla's {@code pickBlockOrEntity} dispatches to
 * {@code MultiPlayerGameMode.handlePickItemFromBlock} with the real-world
 * {@code BlockPos} from the hit. For a structure voxel that position is usually
 * air, so the server picks nothing. This injector consumes the action at the
 * head of the method and sends the structure pick payload instead; the server
 * re-raycasts and resolves the stored block state.</p>
 */
@Mixin(Minecraft.class)
public abstract class StructurePickBlockMixin {
    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void crythst$pickStructureBlock(CallbackInfo callback) {
        final Minecraft minecraft = (Minecraft) (Object) this;
        if (ClientStructureInteraction.requestPickBlock(minecraft)) {
            callback.cancel();
        }
    }
}
