package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.client.render.portal.PortalRenderManager;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the main camera's section-dirty tracker out of the portal extraction.
 *
 * <p>{@code LevelExtractor.extract} re-centres this rotating tracker on the
 * extraction camera and consumes dirty flags for every section in the visible
 * list. A destination camera can be dozens of sections away, so the nested
 * extraction would re-map most of the tracker's nodes (marking them dirty) and
 * then the next main extraction would re-map them back, causing the main view to
 * rebuild nearly every visible section each frame. Portal sections also feed a
 * destination {@code LevelRenderState} that is never applied.</p>
 *
 * <p>Both operations are no-ops only while {@link PortalRenderManager} is inside
 * the nested destination extraction; the main extraction is unaffected.</p>
 */
@Mixin(SectionUpdateTracker.class)
public abstract class PortalSectionUpdateMixin {
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void crythst$skipPortalReposition(SectionPos cameraSectionPos, CallbackInfo ci) {
        if (PortalRenderManager.isDestinationExtracting()) {
            ci.cancel();
        }
    }

    @Inject(method = "getDirtyState", at = @At("HEAD"), cancellable = true)
    private void crythst$skipPortalDirtyState(long sectionNode,
                                              CallbackInfoReturnable<SectionUpdateTracker.SectionDirtyState> cir) {
        if (PortalRenderManager.isDestinationExtracting()) {
            cir.setReturnValue(null);
        }
    }
}
