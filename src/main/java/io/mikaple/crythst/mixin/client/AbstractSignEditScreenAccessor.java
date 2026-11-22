package io.mikaple.crythst.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the four text lines of the vanilla sign editor so the structure
 * editor subclass can send {@code StructureSignUpdatePayload} instead of the
 * vanilla {@code ServerboundSignUpdatePacket} for an auxiliary block position.
 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("messages")
    String[] crythst$messages();
}
