package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.fabric.client.CrythstDebugEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Defaults the Crythst structure-target text entry to the F3 overlay without
 * mutating the immutable profile maps.
 *
 * <p>An explicit player choice in F3 + F6 is stored in {@code allStatuses} and
 * takes precedence; the default only applies while the entry has no stored
 * status.</p>
 */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {
    @Shadow
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "getStatus", at = @At("HEAD"), cancellable = true)
    private void crythst$defaultStructureTargetStatus(Identifier id,
                                                      CallbackInfoReturnable<DebugScreenEntryStatus> cir) {
        if (CrythstDebugEntries.STRUCTURE_TARGET.equals(id)
                && !this.allStatuses.containsKey(id)) {
            cir.setReturnValue(DebugScreenEntryStatus.IN_OVERLAY);
        }
    }
}
