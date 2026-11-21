package io.mikaple.crythst.integration.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodySnapshot;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.StepStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * {@code /crythst} diagnostics and controls.
 */
public final class CrythstCommand {
    private CrythstCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                net.minecraft.commands.CommandBuildContext buildContext,
                                Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("crythst")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(CrythstCommand::status))
                .then(Commands.literal("bodies").executes(CrythstCommand::bodies))
                .then(Commands.literal("pause").executes(context -> setPaused(context, true)))
                .then(Commands.literal("resume").executes(context -> setPaused(context, false)))
                .then(Commands.literal("demo").executes(CrythstCommand::demo))
                .then(Commands.literal("cube").executes(CrythstCommand::cube))
                .then(Commands.literal("unstuck").executes(CrythstCommand::unstuck))
                .then(Commands.literal("clear").executes(CrythstCommand::clear))
                .then(Commands.literal("debug").executes(CrythstCommand::toggleDebug))
                .then(Commands.literal("playergravity")
                        .then(Commands.literal("look").executes(CrythstCommand::playerGravityLook))
                        .then(Commands.literal("reset").executes(CrythstCommand::playerGravityReset))
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(CrythstCommand::playerGravityFixed)))))
                .then(Commands.literal("step").executes(context -> step(context, 1))
                        .then(Commands.argument("count", DoubleArgumentType.doubleArg(1.0, 600.0))
                                .executes(context -> step(context,
                                        (int) DoubleArgumentType.getDouble(context, "count")))))
                .then(Commands.literal("gravity")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(CrythstCommand::gravity)))))
        );
    }

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(source.getLevel());
        if (physics == null) {
            source.sendSuccess(() -> Component.literal("Crythst: no physics world for this dimension yet"), false);
            return 0;
        }
        final StepStats stats = physics.world().lastStepStats();
        final String text = "Crythst " + CrythstApi.backendDescription()
                + " | bodies=" + physics.world().bodyCount()
                + " terrainSections=" + physics.terrainSectionCount()
                + " subLevelChunks=" + physics.subLevelChunkCount()
                + " subLevelRegions=" + physics.subLevelRegionCount()
                + " constraints=" + physics.world().constraintCount()
                + " awake=" + stats.awakeBodyCount()
                + " contacts=" + stats.contactCount()
                + " step=" + String.format("%.3f", stats.stepMillis()) + "ms"
                + " gravity=" + format(physics.world().gravity())
                + " up=" + format(physics.world().up())
                + " origin=" + format(physics.world().origin());
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int bodies(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(source.getLevel());
        if (physics == null) {
            source.sendSuccess(() -> Component.literal("Crythst: no physics world"), false);
            return 0;
        }
        int shown = 0;
        for (BodyHandle body : physics.world().bodies()) {
            if (shown++ >= 20) {
                source.sendSuccess(() -> Component.literal("... output truncated"), false);
                break;
            }
            final BodySnapshot snapshot = body.snapshot();
            source.sendSuccess(() -> Component.literal(
                    "#" + snapshot.id() + " " + snapshot.type()
                            + " pos=" + format(snapshot.transform().position())
                            + " mass=" + String.format("%.2f", snapshot.mass())
                            + " active=" + snapshot.active()), false);
        }
        if (shown == 0) {
            source.sendSuccess(() -> Component.literal("Crythst: no bodies"), false);
        }
        return 1;
    }

    private static int setPaused(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                 boolean paused) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.get(source.getLevel());
        physics.setPaused(paused);
        source.sendSuccess(() -> Component.literal("Crythst physics " + (paused ? "paused" : "resumed")), true);
        return 1;
    }

    private static int step(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int count) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.get(source.getLevel());
        physics.step(count);
        source.sendSuccess(() -> Component.literal("Crythst stepped " + count + " fixed ticks"), true);
        return 1;
    }

    private static int demo(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.get(source.getLevel());
        final int spawned = io.mikaple.crythst.integration.minecraft.DemoSpawner.spawn(
                physics, source.getPosition());
        source.sendSuccess(() -> Component.literal(
                "Crythst demo spawned: " + spawned + " structures. "
                        + "Use /crythst debug to toggle overlays, /crythst clear to remove."), true);
        return spawned;
    }

    private static int cube(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.get(source.getLevel());
        // Spawn a one-metre stone cube a few blocks above the command source so
        // it visibly falls and collides with nearby terrain.
        final net.minecraft.world.phys.Vec3 spawn = source.getPosition().add(0.0, 4.0, 0.0);
        final BodyHandle cube = physics.world().createBody(BodyDef.dynamic(Shapes.cube(1.0))
                .position(new Vector3d(spawn.x, spawn.y, spawn.z))
                .material(PhysicsMaterial.STONE)
                .damping(0.02, 0.05)
                .ccd(true)
                .build());
        physics.ensureTerrain();
        source.sendSuccess(() -> Component.literal("Crythst spawned block body #" + cube.id()
                + " at " + format(cube.transform().position())), true);
        return 1;
    }

    private static int playerGravityLook(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        PlayerGravity.setLook(player);
        source.sendSuccess(() -> Component.literal("Crythst player gravity = look direction"), true);
        return 1;
    }

    private static int playerGravityReset(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        PlayerGravity.reset(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal("Crythst player gravity reset to vanilla down"), true);
        return 1;
    }

    private static int playerGravityFixed(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final double x = DoubleArgumentType.getDouble(context, "x");
        final double y = DoubleArgumentType.getDouble(context, "y");
        final double z = DoubleArgumentType.getDouble(context, "z");
        final net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        PlayerGravity.setFixed(player, new Vector3d(x, y, z));
        source.sendSuccess(() -> Component.literal("Crythst player gravity = "
                + PlayerGravity.describe(player)), true);
        return 1;
    }

    private static int unstuck(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.get(source.getLevel());
        final int pushed = physics.forceUnstuck();
        source.sendSuccess(() -> Component.literal("Crythst unstuck pass: pushed " + pushed + " body(ies)"), true);
        return pushed;
    }

    private static int clear(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(source.getLevel());
        if (physics == null) {
            source.sendSuccess(() -> Component.literal("Crythst: no physics world"), false);
            return 0;
        }
        final int removed = io.mikaple.crythst.integration.minecraft.DemoSpawner.clear(physics);
        source.sendSuccess(() -> Component.literal("Crythst cleared " + removed + " object(s)"), true);
        return removed;
    }

    private static int toggleDebug(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CrythstConfig config = MinecraftPhysics.config();
        config.debug = !config.debug;
        context.getSource().sendSuccess(() -> Component.literal(
                "Crythst debug overlays " + (config.debug ? "enabled" : "disabled")), true);
        return 1;
    }

    private static int gravity(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final double x = DoubleArgumentType.getDouble(context, "x");
        final double y = DoubleArgumentType.getDouble(context, "y");
        final double z = DoubleArgumentType.getDouble(context, "z");
        final ServerLevel level = source.getLevel();
        final LevelPhysics physics = MinecraftPhysics.get(level);
        physics.world().setGravity(new Vector3d(x, y, z));
        PlayerGravity.setWorldDirection(level.dimension(), physics.world().gravity());
        source.sendSuccess(() -> Component.literal("Crythst gravity set to " + format(physics.world().gravity())
                + " (up " + format(physics.world().up()) + ")"), true);
        return 1;
    }

    private static String format(Vector3d value) {
        return String.format("(%.2f, %.2f, %.2f)", value.x, value.y, value.z);
    }
}
