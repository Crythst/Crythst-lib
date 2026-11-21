package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.fabric.client.CrythstDebugEntries;
import io.mikaple.crythst.integration.fabric.client.CrythstPhysicsDebugRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds the Crythst physics overlay to vanilla's active debug renderer list
 * when its F3 + F6 entry is enabled.
 */
@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {
    @Shadow
    @Final
    private List<DebugRenderer.SimpleDebugRenderer> renderers;

    @Inject(method = "refreshRendererList", at = @At("TAIL"))
    private void crythst$addPhysicsRenderer(CallbackInfo ci) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.debugEntries.isCurrentlyEnabled(CrythstDebugEntries.PHYSICS)) {
            this.renderers.add(new CrythstPhysicsDebugRenderer());
        }
    }
}
