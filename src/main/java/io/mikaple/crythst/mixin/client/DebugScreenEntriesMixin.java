package io.mikaple.crythst.mixin.client;

import io.mikaple.crythst.integration.fabric.client.CrythstDebugEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Registers Crythst's physics overlay in the vanilla debug-entry registry.
 *
 * <p>Running at the tail of the static initializer means all vanilla entries
 * exist first; the new entry has no profile assignment, so
 * {@code DebugScreenEntryList} treats it as {@code NEVER} until the player
 * enables it in F3 + F6.</p>
 */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
    @Shadow
    @Final
    private static Map<Identifier, DebugScreenEntry> ENTRIES_BY_ID;

    @Inject(method = "allEntries", at = @At("TAIL"))
    private static void crythst$registerPhysicsEntry(CallbackInfoReturnable<Map<Identifier, DebugScreenEntry>> cir) {
        for (Identifier id : CrythstDebugEntries.ALL) {
            ENTRIES_BY_ID.put(id, CrythstDebugEntries.ENTRY);
        }
    }
}
