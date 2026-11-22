package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.interaction.StructureSignOpenPayload;
import io.mikaple.crythst.integration.minecraft.interaction.StructureSignUpdatePayload;
import io.mikaple.crythst.mixin.client.AbstractSignEditScreenAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HangingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client receiver and editor screens for structure signs.
 *
 * <p>The vanilla editor always sends its text to {@code sign.getBlockPos()}.
 * For a structure sign that position is an auxiliary-level coordinate the
 * host server cannot resolve, so these screens replace the send with
 * {@link StructureSignUpdatePayload} carrying the body id and structure-local
 * voxel.</p>
 */
@Environment(EnvType.CLIENT)
public final class StructureSignClient {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureSignClient() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ClientPlayNetworking.registerGlobalReceiver(StructureSignOpenPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> open(context.client(), payload)));
    }

    private static void open(Minecraft minecraft, StructureSignOpenPayload payload) {
        final ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        try {
            final BlockState state = Block.stateById(payload.blockStateId());
            final BlockPos pos = payload.worldPos();
            final BlockEntity blockEntity = BlockEntity.loadStatic(
                    pos, state, payload.signData(), level.registryAccess());
            if (!(blockEntity instanceof SignBlockEntity sign)) {
                Crythst.LOGGER.debug(
                        "Structure sign editor payload did not load a sign for body #{}",
                        payload.bodyId());
                return;
            }
            sign.setLevel(level);
            final boolean filtering = minecraft.isTextFilteringEnabled();
            final Screen screen = state.getBlock() instanceof HangingSignBlock
                    ? new StructureHangingSignEditScreen(sign, payload.slot(), filtering, payload)
                    : new StructureSignEditScreen(sign, payload.slot(), filtering, payload);
            minecraft.gui.setScreen(screen);
        } catch (RuntimeException e) {
            // A modded sign block must not break the client tick loop.
            Crythst.LOGGER.warn("Could not open structure sign editor for body #{}",
                    payload.bodyId(), e);
        }
    }

    private static void sendUpdate(Minecraft minecraft, Screen screen,
                                   StructureSignOpenPayload target) {
        final String[] lines = ((AbstractSignEditScreenAccessor) screen)
                .crythst$messages().clone();
        if (minecraft.getConnection() != null
                && ClientPlayNetworking.canSend(StructureSignUpdatePayload.TYPE)) {
            ClientPlayNetworking.send(new StructureSignUpdatePayload(
                    target.bodyId(), target.voxelX(), target.voxelY(), target.voxelZ(),
                    target.slot(), List.of(lines)));
        }
        minecraft.textInputManager().stopTextInput(screen);
    }

    private static final class StructureSignEditScreen extends SignEditScreen {
        private final StructureSignOpenPayload target;

        private StructureSignEditScreen(SignBlockEntity sign, SignTextSlot slot,
                                        boolean filtering, StructureSignOpenPayload target) {
            super(sign, slot, filtering);
            this.target = target;
        }

        @Override
        public void removed() {
            sendUpdate(this.minecraft, this, target);
        }
    }

    private static final class StructureHangingSignEditScreen extends HangingSignEditScreen {
        private final StructureSignOpenPayload target;

        private StructureHangingSignEditScreen(SignBlockEntity sign, SignTextSlot slot,
                                               boolean filtering,
                                               StructureSignOpenPayload target) {
            super(sign, slot, filtering);
            this.target = target;
        }

        @Override
        public void removed() {
            sendUpdate(this.minecraft, this, target);
        }
    }
}
