package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.fabric.client.CrythstDebugEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds Crythst entries to vanilla's debug-entry views without mutating its
 * immutable registry.
 *
 * <p>{@code DebugScreenEntries.ENTRIES_BY_ID} is an unmodifiable map after
 * class initialization, so a direct {@code put} is not possible. The options
 * screen and entry list consume {@link DebugScreenEntries#allEntries()} and
 * {@link DebugScreenEntries#getEntry(Identifier)}, which this mixin extends
 * with a merged view. Physics renderers stay unassigned (default
 * {@code NEVER}); the structure-target text entry defaults through
 * {@code DebugScreenEntryListMixin}.</p>
 */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
    @Inject(method = "allEntries", at = @At("RETURN"), cancellable = true)
    private static void crythst$includeDebugEntries(
            CallbackInfoReturnable<Map<Identifier, DebugScreenEntry>> cir) {
        final Map<Identifier, DebugScreenEntry> merged =
                new LinkedHashMap<>(cir.getReturnValue());
        for (Identifier id : CrythstDebugEntries.ALL_RENDERERS) {
            merged.put(id, CrythstDebugEntries.ENTRY);
        }
        merged.put(CrythstDebugEntries.STRUCTURE_TARGET,
                CrythstDebugEntries.STRUCTURE_TARGET_ENTRY);
        merged.put(CrythstDebugEntries.PORTAL_STATS,
                CrythstDebugEntries.PORTAL_STATS_ENTRY);
        cir.setReturnValue(Collections.unmodifiableMap(merged));
    }

    @Inject(method = "getEntry", at = @At("HEAD"), cancellable = true)
    private static void crythst$getDebugEntry(Identifier id,
                                              CallbackInfoReturnable<DebugScreenEntry> cir) {
        if (id == null) {
            return;
        }
        if (id.equals(CrythstDebugEntries.STRUCTURE_TARGET)) {
            cir.setReturnValue(CrythstDebugEntries.STRUCTURE_TARGET_ENTRY);
            return;
        }
        if (id.equals(CrythstDebugEntries.PORTAL_STATS)) {
            cir.setReturnValue(CrythstDebugEntries.PORTAL_STATS_ENTRY);
            return;
        }
        if (CrythstDebugEntries.ALL_RENDERERS.contains(id)) {
            cir.setReturnValue(CrythstDebugEntries.ENTRY);
        }
    }
}
