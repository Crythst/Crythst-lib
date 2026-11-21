package io.mikaple.crythst.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's private pick-block completion so structure pick-block uses
 * the exact creative/survival inventory behaviour.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplInvoker {
    @Invoker("tryPickItem")
    void crythst$tryPickItem(ItemStack stack);
}
