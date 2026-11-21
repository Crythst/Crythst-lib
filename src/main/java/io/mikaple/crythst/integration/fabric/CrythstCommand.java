package io.mikaple.crythst.integration.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.entity.EntityPhysicsCollisionManager;
import io.mikaple.crythst.integration.minecraft.entity.EntityStructureSolver;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.body.BodySnapshot;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.StepStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
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
                .then(Commands.literal("timing").executes(CrythstCommand::timing))
                .then(Commands.literal("bodies").executes(CrythstCommand::bodies))
                .then(Commands.literal("pause").executes(context -> setPaused(context, true)))
                .then(Commands.literal("resume").executes(context -> setPaused(context, false)))
                .then(Commands.literal("demo").executes(CrythstCommand::demo))
                .then(Commands.literal("cube").executes(CrythstCommand::cube))
                .then(Commands.literal("align").executes(CrythstCommand::align))
                .then(Commands.literal("unstuck").executes(CrythstCommand::unstuck))
                .then(Commands.literal("clear").executes(CrythstCommand::clear))
                .then(Commands.literal("debug").executes(CrythstCommand::toggleDebug))
                .then(Commands.literal("entitydebug").executes(CrythstCommand::entityDebug))
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
                + " projectedEntities=" + physics.projectedEntityCount()
                + " voidEntities=" + physics.subLevelEntityCount()
                + " voidTicks=" + physics.lastVoidTick().scheduledTicks()
                + " voidRandomTicks=" + physics.lastVoidTick().randomTicks()
                + " voidEvents=" + physics.lastVoidTick().blockEvents()
                + " voidMirrored=" + physics.lastVoidTick().mirroredBlocks()
                + " voidDropped=" + physics.lastVoidTick().dropped()
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

    /**
     * Prints the fixed-step timing, render interpolation and jitter estimate for
     * this dimension.
     */
    private static int timing(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(source.getLevel());
        if (physics == null) {
            source.sendSuccess(() -> Component.literal(
                    "Crythst timing: no physics world for this dimension yet"), false);
            return 0;
        }
        // Pose diagnostics live in the client renderer, which is absent on a
        // dedicated server. Resolve them reflectively so this command still
        // works server-side.
        String poseDiagnostics = "";
        try {
            final Class<?> renderer = Class.forName(
                    "io.mikaple.crythst.client.render.structure.batch.BatchedStructureRenderer");
            poseDiagnostics = String.valueOf(renderer.getMethod("poseDiagnosticsText").invoke(null));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            poseDiagnostics = "";
        }
        final String suffix = poseDiagnostics.isEmpty() ? "" : " | " + poseDiagnostics;
        source.sendSuccess(() -> Component.literal(physics.timingText() + suffix), false);
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
        // Spawn a real one-block physics structure, not a bare collision shape,
        // so the batched renderer draws the stone block and interaction works.
        final net.minecraft.world.phys.Vec3 spawn = source.getPosition().add(0.0, 4.0, 0.0);
        final MutableVoxelVolume volume = VoxelVolumes.filled(
                new IntBounds3(0, 0, 0, 1, 1, 1),
                MinecraftPhysics.config().defaultBlockMass, PhysicsMaterial.STONE);
        final PhysicsStructure cube = physics.createStructure(StructureDef.builder(volume)
                .type(BodyType.DYNAMIC)
                .pose(Transform3d.of(new Vector3d(
                        spawn.x - 0.5, spawn.y - 0.5, spawn.z - 0.5)))
                .material(PhysicsMaterial.STONE)
                .damping(0.02, 0.05)
                .ccd(true)
                .build());
        StructureBlockStates.set(cube, 0, 0, 0, Blocks.STONE.defaultBlockState());
        physics.ensureTerrain();
        source.sendSuccess(() -> Component.literal("Crythst spawned block structure #"
                + cube.body().id() + " at " + format(cube.body().transform().position())), true);
        return 1;
    }

    /**
     * Prints the body-pose / structure-pose / COM relationship and compares a
     * sample voxel transformed by the collision convention with the same voxel
     * transformed by the render convention.
     */
    private static int align(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(source.getLevel());
        if (physics == null) {
            source.sendSuccess(() -> Component.literal("Crythst: no physics world"), false);
            return 0;
        }
        final var structures = physics.structures();
        if (structures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Crythst align: no structures"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Crythst align: body = structure + R*COM; collision = body * (voxel - COM); "
                        + "render = structure * voxel"), false);
        int shown = 0;
        for (PhysicsStructure structure : structures) {
            if (shown++ >= 8) {
                source.sendSuccess(() -> Component.literal("... align output truncated"), false);
                break;
            }
            final Transform3d body = structure.body().transform();
            final Transform3d structurePose = structure.pose();
            final StructureRenderState state = structure.renderState(0.0);
            final Vector3d com = state.centerOfMassOffset();
            final double scale = state.blockScale();
            final Vector3d rotatedCom = new Vector3d(com).rotate(body.rotation());
            final Vector3d expectedStructurePosition = new Vector3d(body.position()).sub(rotatedCom);
            final double conventionError = expectedStructurePosition.distance(structurePose.position());

            if (structure.collider().boxes().isEmpty()) {
                continue;
            }
            final VoxelBox box = structure.collider().boxes().get(0);
            final double voxelX = box.minX() + 0.5;
            final double voxelY = box.minY() + 0.5;
            final double voxelZ = box.minZ() + 0.5;
            final Vector3d voxelMetres = new Vector3d(
                    voxelX * scale, voxelY * scale, voxelZ * scale);
            final Vector3d collisionWorld = body.transformPoint(
                    new Vector3d(voxelMetres).sub(com));
            final Vector3d renderWorld = structurePose.transformPoint(new Vector3d(voxelMetres));
            final double offset = collisionWorld.distance(renderWorld);

            final String text = "#" + structure.body().id()
                    + " bodyPos=" + format(body.position())
                    + " structurePos=" + format(structurePose.position())
                    + " com=" + format(com)
                    + " R*com=" + format(rotatedCom)
                    + " conventionErr=" + String.format("%.9f", conventionError)
                    + " sampleVoxelCentre=" + format(voxelMetres)
                    + " collisionWorld=" + format(collisionWorld)
                    + " renderWorld=" + format(renderWorld)
                    + " offset=" + String.format("%.9f", offset);
            source.sendSuccess(() -> Component.literal(text), false);
        }
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

    /**
     * Prints the entity/pose/local-query/MTV data used by the one-way
     * collision pass. Inspects entities within eight blocks so a mob or item
     * near the command source can be diagnosed as well as the player.
     */
    private static int entityDebug(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final net.minecraft.world.phys.Vec3 sourcePos = source.getPosition();
        final java.util.List<EntityPhysicsCollisionManager.DebugReport> reports =
                EntityPhysicsCollisionManager.inspectNear(level,
                        new Vector3d(sourcePos.x, sourcePos.y, sourcePos.z), 8.0, 4);
        if (reports.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "entitydebug: no entities within 8 blocks"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "entitydebug: margin=" + String.format("%.3f", EntityPhysicsCollisionManager.effectiveMargin())
                        + " stats=" + EntityPhysicsCollisionManager.lastStats()), false);
        for (EntityPhysicsCollisionManager.DebugReport report : reports) {
            final String header = "entity " + report.entityType()
                    + " id=" + report.entityUuid()
                    + " pos=" + format(report.position())
                    + " aabb=" + format(report.entityBox().min())
                    + ".." + format(report.entityBox().max());
            source.sendSuccess(() -> Component.literal(header), false);
            if (report.structures().isEmpty()) {
                source.sendSuccess(() -> Component.literal("  no nearby structures"), false);
                continue;
            }
            for (EntityPhysicsCollisionManager.StructureDebug structure : report.structures()) {
                final EntityStructureSolver.Hit hit = structure.hit();
                final String hitText = hit == null
                        ? "hit=none"
                        : "hit normal=" + format(hit.normal())
                        + " depth=" + String.format("%.4f", hit.penetration())
                        + " offset=" + format(hit.offset());
                final String line = "  structure #" + structure.bodyId()
                        + " posePos=" + format(structure.pose().position())
                        + " poseRot=" + formatRotation(structure.pose().rotation())
                        + " bounds=" + format(structure.bounds().min())
                        + ".." + format(structure.bounds().max())
                        + " localQuery=" + format(structure.localQuery().min())
                        + ".." + format(structure.localQuery().max())
                        + " " + hitText
                        + " centerInside=" + structure.centerInside();
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    private static String formatRotation(org.joml.Quaterniondc rotation) {
        return String.format("(%.3f, %.3f, %.3f, %.3f)",
                rotation.x(), rotation.y(), rotation.z(), rotation.w());
    }

    private static String format(Vector3d value) {
        return String.format("(%.2f, %.2f, %.2f)", value.x, value.y, value.z);
    }
}
