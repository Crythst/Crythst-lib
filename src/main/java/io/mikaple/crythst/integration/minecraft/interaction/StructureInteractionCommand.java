package io.mikaple.crythst.integration.minecraft.interaction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Diagnostic and test commands for structure interaction.
 *
 * <p>{@code /crythstinteraction ray} prints exactly what the server sees for
 * the current crosshair: body id, volume class, editability, voxel, face,
 * distance and stored state. {@code /crythstmine} and {@code /crythstplace}
 * are kept as short aliases. None of these commands depend on the client sync
 * or payload path, so they isolate server-side raycast/volume problems from
 * client input problems.</p>
 */
public final class StructureInteractionCommand {
    private StructureInteractionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("crythstinteraction")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("ray").executes(StructureInteractionCommand::inspect))
                .then(Commands.literal("mine").executes(StructureInteractionCommand::mine))
                .then(Commands.literal("place").executes(StructureInteractionCommand::place))
                .then(Commands.literal("water").executes(StructureInteractionCommand::water)));
        dispatcher.register(Commands.literal("crythstmine")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(StructureInteractionCommand::mine));
        dispatcher.register(Commands.literal("crythstplace")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(StructureInteractionCommand::place));
    }

    public static int inspect(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player = source.getPlayerOrException();
        final StructureBlockHitResult hit = StructureInteraction.raycast(player);
        if (hit == null) {
            source.sendFailure(Component.literal(
                    "Crythst interaction: no structure under the crosshair within "
                            + String.format("%.2f", player.blockInteractionRange()) + " blocks"));
            return 0;
        }
        final BlockState state = StructureInteraction.interactBlock(player, hit);
        final var structure = hit.structure();
        final var volume = structure.volume();
        source.sendSuccess(() -> Component.literal(
                "Crythst interaction: body=#" + structure.body().id()
                        + " type=" + structure.body().type()
                        + " voxel=(" + hit.voxelX() + "," + hit.voxelY() + "," + hit.voxelZ() + ")"
                        + " face=" + hit.localFace()
                        + " distance=" + String.format("%.2f", hit.distance())
                        + " volume=" + volume.getClass().getSimpleName()
                        + " bounds=" + volume.bounds()
                        + " editable=" + StructureInteraction.isEditable(structure)
                        + " state=" + (state == null ? "none" : state.getBlock().getName().getString())
                        + " sideStates=" + StructureBlockStates.size(structure)), false);
        return 1;
    }

    public static int mine(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player = source.getPlayerOrException();
        final StructureBlockHitResult hit = StructureInteraction.raycast(player);
        if (hit == null) {
            source.sendFailure(Component.literal("Crythst: no physics structure in reach"));
            return 0;
        }
        final StructureInteraction.BreakResult result = StructureInteraction.breakBlock(player, hit);
        if (!result.success()) {
            source.sendFailure(Component.literal("Crythst: structure voxel is read-only or already empty"
                    + " (volume=" + hit.structure().volume().getClass().getSimpleName()
                    + ", editable=" + StructureInteraction.isEditable(hit.structure()) + ")"));
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
        final StructureBlockHitResult hit = StructureInteraction.raycast(player);
        if (hit == null) {
            source.sendFailure(Component.literal("Crythst: no physics structure in reach"));
            return 0;
        }
        final StructureInteraction.PlaceResult result = StructureInteraction.placeBlock(
                player, hit, stack);
        if (!result.success()) {
            source.sendFailure(Component.literal(
                    "Crythst: target voxel is occupied or read-only"
                            + " (volume=" + hit.structure().volume().getClass().getSimpleName()
                            + ", editable=" + StructureInteraction.isEditable(hit.structure()) + ")"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Crythst placed "
                + blockItem.getBlock().getName().getString() + " at structure voxel ("
                + result.x() + "," + result.y() + "," + result.z() + ")"), true);
        return 1;
    }

    public static int water(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player = source.getPlayerOrException();
        final boolean recoiled = StructureInteraction.shovelWater(player);
        if (recoiled) {
            source.sendSuccess(() -> Component.literal("Crythst shovel-water recoil applied"), true);
            return 1;
        }
        source.sendFailure(Component.literal(
                "Crythst: need a shovel in the main hand and a water ray in reach"));
        return 0;
    }
}
