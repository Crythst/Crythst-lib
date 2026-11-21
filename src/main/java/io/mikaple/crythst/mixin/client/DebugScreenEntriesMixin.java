package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.fabric.client.CrythstDebugEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Registers Crythst's debug entries in the vanilla debug-entry registry.
 *
 * <p>Running at the tail of the static initializer means all vanilla entries
 * and the profile maps exist first. Physics renderer entries are left out of
 * the profiles (default {@code NEVER}); the structure-target text entry is
 * added to every profile as {@code IN_OVERLAY}, so it appears in F3 unless the
 * player disables it in F3 + F6.</p>
 */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
    @Shadow
    @Final
    private static Map<Identifier, DebugScreenEntry> ENTRIES_BY_ID;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void crythst$registerDebugEntries(CallbackInfo ci) {
        for (Identifier id : CrythstDebugEntries.ALL_RENDERERS) {
            ENTRIES_BY_ID.put(id, CrythstDebugEntries.ENTRY);
        }
        ENTRIES_BY_ID.put(CrythstDebugEntries.STRUCTURE_TARGET,
                CrythstDebugEntries.STRUCTURE_TARGET_ENTRY);
        for (Map<Identifier, DebugScreenEntryStatus> profile : DebugScreenEntries.PROFILES.values()) {
            profile.putIfAbsent(CrythstDebugEntries.STRUCTURE_TARGET,
                    DebugScreenEntryStatus.IN_OVERLAY);
        }
    }
}
