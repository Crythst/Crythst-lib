package io.mikaple.crythst.integration.minecraft.interaction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Diagnostic commands for testing interaction without a synced client.
 *
 * <p>These are registered as {@code /crythstmine} and {@code /crythstplace} so
 * they do not modify the shared {@code /crythst} command tree. To expose them
 * as {@code /crythst mine} and {@code /crythst place}, add the two lines
 * documented in {@code docs/player-structure-interaction.md} to
 * {@code CrythstCommand.register}.</p>
 */
public final class StructureInteractionCommand {
    private StructureInteractionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("crythstmine")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(StructureInteractionCommand::mine));
        dispatcher.register(Commands.literal("crythstplace")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(StructureInteractionCommand::place));
    }

    public static int mine(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player = source.getPlayerOrException();
        final StructureHit hit = StructureInteraction.raycast(player);
        if (hit == null) {
            source.sendFailure(Component.literal("Crythst: no physics structure in reach"));
            return 0;
        }
        final StructureInteraction.BreakResult result = StructureInteraction.breakBlock(player, hit);
        if (!result.success()) {
            source.sendFailure(Component.literal("Crythst: structure voxel is read-only or already empty"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Crythst mined structure #"
                + hit.structure().body().id() + " voxel (" + hit.voxelX() + ","
                + hit.voxelY() + "," + hit.voxelZ() + ")"
                + (result.structureRemoved() ? "; structure body removed" : "")), true);
        return 1;
    }

    public static int place(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player = source.getPlayerOrException();
        final ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            source.sendFailure(Component.literal(
                    "Crythst: hold a block item in the main hand to place into a structure"));
            return 0;
        }
        final StructureHit hit = StructureInteraction.raycast(player);
        if (hit == null) {
            source.sendFailure(Component.literal("Crythst: no physics structure in reach"));
            return 0;
        }
        final StructureInteraction.PlaceResult result = StructureInteraction.placeBlock(
                player, hit, blockItem.getBlock().defaultBlockState());
        if (!result.success()) {
            source.sendFailure(Component.literal(
                    "Crythst: target voxel is occupied, outside the volume or read-only"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Crythst placed "
                + blockItem.getBlock().getName().getString() + " at structure voxel ("
                + result.x() + "," + result.y() + "," + result.z() + ")"), true);
        return 1;
    }
}
