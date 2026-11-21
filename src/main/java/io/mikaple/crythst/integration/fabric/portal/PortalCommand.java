package io.mikaple.crythst.integration.fabric.portal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.mikaple.crythst.integration.portal.Portal;
import io.mikaple.crythst.integration.portal.PortalManager;
import io.mikaple.crythst.integration.portal.PortalPlane;
import io.mikaple.crythst.integration.portal.PortalTransform;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

/**
 * {@code /crythst portal ...} commands for the static-portal milestone.
 *
 * <p>Merges into the existing {@code /crythst} root: Brigadier merges literal
 * roots and children by name, so no change to {@code CrythstCommand} is
 * needed.</p>
 */
public final class PortalCommand {
    /** Distance from the creator's eye to the new plane. */
    private static final double PLACEMENT_DISTANCE = 2.0;
    private static final double DEFAULT_WIDTH = 2.0;
    private static final double DEFAULT_HEIGHT = 3.0;
    private static final double MIN_SIZE = 0.5;
    private static final double MAX_SIZE = 16.0;

    private PortalCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                CommandSelection selection) {
        dispatcher.register(Commands.literal("crythst")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("portal")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> create(context,
                                                DEFAULT_WIDTH, DEFAULT_HEIGHT))
                                        .then(Commands.argument("width",
                                                        DoubleArgumentType.doubleArg(MIN_SIZE, MAX_SIZE))
                                                .executes(context -> create(context,
                                                        DoubleArgumentType.getDouble(context, "width"),
                                                        DEFAULT_HEIGHT))
                                                .then(Commands.argument("height",
                                                                DoubleArgumentType.doubleArg(MIN_SIZE, MAX_SIZE))
                                                        .executes(context -> create(context,
                                                                DoubleArgumentType.getDouble(context, "width"),
                                                                DoubleArgumentType.getDouble(context, "height")))))))
                        .then(Commands.literal("link")
                                .then(Commands.argument("first", StringArgumentType.word())
                                        .then(Commands.argument("second", StringArgumentType.word())
                                                .executes(PortalCommand::link))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(PortalCommand::remove)))
                        .then(Commands.literal("list")
                                .executes(PortalCommand::list))));
    }

    private static int create(CommandContext<CommandSourceStack> context, double width, double height) {
        final CommandSourceStack source = context.getSource();
        final String name = StringArgumentType.getString(context, "name");
        if (PortalManager.findByName(name) != null) {
            source.sendFailure(Component.literal("Crythst: portal '" + name + "' already exists"));
            return 0;
        }

        final Vec3 eye = eyePosition(source);
        final Vec3 look = lookDirection(source);
        final PortalPlane plane = PortalPlane.fromLook(
                new Vector3d(eye.x, eye.y, eye.z),
                new Vector3d(look.x, look.y, look.z),
                PLACEMENT_DISTANCE, width, height);

        try {
            final Portal portal = PortalManager.create(source.getLevel().dimension(), name, plane);
            source.sendSuccess(() -> Component.literal(String.format(
                    "Crythst: created portal '%s' (%s) at (%.2f, %.2f, %.2f), normal (%.2f, %.2f, %.2f), %.1fx%.1f",
                    portal.name(), portal.dimension().identifier(),
                    plane.origin().x, plane.origin().y, plane.origin().z,
                    plane.normal().x, plane.normal().y, plane.normal().z,
                    plane.width(), plane.height())), true);
            return 1;
        } catch (IllegalArgumentException error) {
            source.sendFailure(Component.literal("Crythst: " + error.getMessage()));
            return 0;
        }
    }

    private static int link(CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String first = StringArgumentType.getString(context, "first");
        final String second = StringArgumentType.getString(context, "second");

        final Portal a = PortalManager.findByName(first);
        final Portal b = PortalManager.findByName(second);
        if (a == null) {
            source.sendFailure(Component.literal("Crythst: unknown portal '" + first + "'"));
            return 0;
        }
        if (b == null) {
            source.sendFailure(Component.literal("Crythst: unknown portal '" + second + "'"));
            return 0;
        }
        if (a == b) {
            source.sendFailure(Component.literal("Crythst: cannot link a portal to itself"));
            return 0;
        }
        if (!a.dimension().equals(b.dimension())) {
            source.sendSuccess(() -> Component.literal(
                    "Crythst: linking portals in different dimensions; entities may cross, "
                            + "physics bodies will not (v1 limitation)"), false);
        }

        final PortalTransform transform = PortalManager.link(first, second);
        if (transform == null) {
            source.sendFailure(Component.literal("Crythst: could not link portals"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Crythst: linked '" + first + "' <-> '" + second + "'"), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String name = StringArgumentType.getString(context, "name");
        final Portal removed = PortalManager.remove(name);
        if (removed == null) {
            source.sendFailure(Component.literal("Crythst: unknown portal '" + name + "'"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Crythst: removed portal '" + name + "'"), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final List<Portal> portals = PortalManager.all();
        if (portals.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Crythst: no portals. Use /crythst portal create <name> [width] [height]"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Crythst: " + portals.size() + " portal(s)"), false);
        for (Portal portal : portals) {
            final Portal linked = PortalManager.linkedPortal(portal);
            final String linkedName = linked == null ? "-" : linked.name();
            final PortalPlane plane = portal.plane();
            source.sendSuccess(() -> Component.literal(String.format(
                    "  %s [%s] origin=(%.2f, %.2f, %.2f) %.1fx%.1f linked=%s",
                    portal.name(), portal.dimension().identifier(),
                    plane.origin().x, plane.origin().y, plane.origin().z,
                    plane.width(), plane.height(), linkedName)), false);
        }
        return portals.size();
    }

    private static Vec3 eyePosition(CommandSourceStack source) {
        final Entity entity = source.getEntity();
        if (entity != null) {
            return entity.getEyePosition();
        }
        return source.getPosition().add(0.0, 1.62, 0.0);
    }

    private static Vec3 lookDirection(CommandSourceStack source) {
        final Entity entity = source.getEntity();
        if (entity != null) {
            return entity.getLookAngle();
        }
        final Vec2 rotation = source.getRotation();
        return Entity.calculateViewVector(rotation.x, rotation.y);
    }
}
