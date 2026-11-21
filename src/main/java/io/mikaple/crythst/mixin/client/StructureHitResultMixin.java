package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureTargeting;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces {@code Minecraft.hitResult} with the structure voxel under the
 * crosshair when it is closer than vanilla's own pick.
 *
 * <p>Doing this at the tail of {@code Minecraft.pick} means the outline,
 * crosshair and use/attack paths all observe the same target for the whole
 * frame. A real block or entity that is closer is left untouched.</p>
 */
@Mixin(Minecraft.class)
public abstract class StructureHitResultMixin {
    @Inject(method = "pick", at = @At("TAIL"))
    private void crythst$installStructureHitResult(float partialTick, CallbackInfo callback) {
        ClientStructureTargeting.afterPick((Minecraft) (Object) this);
    }
}
