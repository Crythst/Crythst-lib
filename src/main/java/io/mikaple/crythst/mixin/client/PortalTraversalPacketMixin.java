package io.mikaple.crythst.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.mikaple.crythst.client.render.portal.PortalCrossingController;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

@Mixin(ClientPacketListener.class)
public abstract class PortalTraversalPacketMixin {
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;setValuesFromPositionPacket(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;Lnet/minecraft/world/entity/Entity;Z)Z"))
    private boolean crythst$preservePortalInterpolation(PositionMoveRotation target, Set<Relative> relatives,
                                                         Entity entity, boolean interpolate,
                                                         Operation<Boolean> original) {
        if (!(entity instanceof LocalPlayer player) || !relatives.isEmpty()
                || !PortalCrossingController.acknowledge(player, target)) {
            return original.call(target, relatives, entity, interpolate);
        }
        final Vec3 oldPosition = player.oldPosition();
        final float oldYaw = player.yRotO;
        final float oldPitch = player.xRotO;
        final boolean result = original.call(target, relatives, entity, interpolate);
        // The packet still controls the current pose and its vanilla ACK. Its
        // arrival should not erase the start of the local interpolation.
        player.setOldPosAndRot(oldPosition, oldYaw, oldPitch);
        return result;
    }
}
