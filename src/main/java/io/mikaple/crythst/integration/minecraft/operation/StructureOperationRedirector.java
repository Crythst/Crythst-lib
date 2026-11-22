package io.mikaple.crythst.integration.minecraft.operation;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Routes structure break/use/placement through the auxiliary void level so
 * vanilla block, block-entity and placement logic runs in a real
 * {@code ServerLevel}.
 *
 * <p>This is the P0 "operation position redirect" helper. It deliberately does
 * not replace raycasting or validation: callers still obtain the authoritative
 * {@link StructureBlockHitResult} from the normal server-side structure
 * raycast. The redirector only turns that hit into an auxiliary-level
 * {@link BlockHitResult} and invokes vanilla
 * {@link ServerPlayerGameMode#useItemOn} / {@link ServerPlayerGameMode#destroyBlock}
 * against the void level. It then asks {@link LevelPhysics} to mirror block
 * changes and project any spawned drops/entities back to the host world.</p>
 *
 * <p><strong>Feature flag:</strong> all entry points return
 * {@link Status#DISABLED} unless {@link CrythstConfig#useVoidLevelOperations}
 * is enabled. It is enabled by default; the existing custom interaction path is
 * retained only as a fallback when the auxiliary region/block is unavailable or
 * when a placement would grow the copied volume.</p>
 *
 * <p><strong>Integration points:</strong> the server right-click handler calls
 * {@link #useOn(ServerPlayer, StructureBlockHitResult, InteractionHand)} before
 * its custom use logic; the mining/break handler calls
 * {@link #breakBlock(ServerPlayer, StructureBlockHitResult)} (or
 * {@link #breakMapped(ServerPlayer, Mapped)} for a session) after its normal
 * reach/occupancy validation. Callers must treat {@link Status#HANDLED} and
 * {@link Status#FAILED} as consuming the real-world action;
 * {@link #shouldFallback(Status)} marks the statuses that may run the custom
 * path. In particular {@link Status#OUT_OF_BOUNDS} falls back to volume
 * expansion, and {@link Status#NOT_HANDLED} (for example an unloaded auxiliary
 * chunk) falls back to the custom edit.</p>
 */
public final class StructureOperationRedirector {
    private StructureOperationRedirector() {
    }

    /** Outcome of one redirected operation. */
    public enum Status {
        /** {@code useVoidLevelOperations} is off. */
        DISABLED,
        /** The structure/auxiliary block is unavailable; use the custom path. */
        NOT_HANDLED,
        /** The operation ran in the void level and was mirrored. */
        HANDLED,
        /** Vanilla returned a failure for the operation. */
        FAILED,
        /** A block-item placement target would be outside the copied volume. */
        OUT_OF_BOUNDS
    }

    /**
     * A structure hit mapped to the auxiliary level.
     *
     * @param structure   owning structure
     * @param live        live auxiliary block and block entity
     * @param auxiliaryHit hit expressed in the auxiliary level's coordinate
     *                     space (same local face/fraction as the world hit)
     */
    public record Mapped(PhysicsStructure structure, EmptyPhysicsLevel.LiveBlock live,
                         BlockHitResult auxiliaryHit) {
    }

    /**
     * One redirected operation result. {@code interaction} is the vanilla
     * result for use operations, or {@link InteractionResult#SUCCESS} /
     * {@link InteractionResult#FAIL} for break operations.
     */
    public record Result(Status status, InteractionResult interaction) {
        public static Result of(Status status) {
            return new Result(status, status == Status.HANDLED
                    ? InteractionResult.SUCCESS : InteractionResult.PASS);
        }

        public boolean handled() {
            return status == Status.HANDLED;
        }
    }

    /** Whether the redirect feature is enabled. */
    public static boolean enabled() {
        final CrythstConfig config = MinecraftPhysics.config();
        return config != null && config.useVoidLevelOperations
                && config.structureBlockInteractionEnabled;
    }

    /**
     * Whether a redirect result permits the legacy custom interaction path.
     *
     * <p>{@link Status#HANDLED} and {@link Status#FAILED} already ran vanilla
     * logic (or deliberately decided not to edit), so falling back would apply
     * a second edit. Only a disabled redirect, an unavailable auxiliary region
     * and an out-of-bounds placement are safe to retry; the last one uses the
     * volume-expanding custom placement.</p>
     */
    public static boolean shouldFallback(Status status) {
        return status == Status.DISABLED
                || status == Status.NOT_HANDLED
                || status == Status.OUT_OF_BOUNDS;
    }

    /**
     * Maps an authoritative structure hit to the auxiliary level.
     *
     * @return the mapping, or {@code null} when the redirect is disabled, the
     *         structure is closed, the hit is out of reach or the live
     *         auxiliary chunk/block is unavailable
     */
    public static @Nullable Mapped map(ServerPlayer player, StructureBlockHitResult hit) {
        if (!enabled() || player == null || hit == null || hit.structure() == null) {
            return null;
        }
        final PhysicsStructure structure = hit.structure();
        if (structure.closed()
                || !(hit.distance() <= player.blockInteractionRange() + 1.0e-6)) {
            return null;
        }
        if (!(player.level() instanceof ServerLevel hostLevel)) {
            return null;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(hostLevel);
        if (physics == null) {
            return null;
        }
        final EmptyPhysicsLevel.LiveBlock live = physics.liveBlock(
                structure, hit.voxelX(), hit.voxelY(), hit.voxelZ());
        if (live == null) {
            return null;
        }
        return new Mapped(structure, live,
                auxiliaryHit(hit, live.pos()));
    }

    /**
     * Invokes vanilla right-click use in the void level. This covers menu
     * providers, {@code BlockState.useItemOn}, {@code useWithoutItem} and
     * {@code ItemStack.useOn} through the real player game mode.
     */
    public static Result useOn(ServerPlayer player, StructureBlockHitResult hit,
                               InteractionHand hand) {
        final Mapped mapped = map(player, hit);
        if (mapped == null) {
            return Result.of(enabled() ? Status.NOT_HANDLED : Status.DISABLED);
        }
        final ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof BlockItem
                && !placementTargetInsideVolume(mapped, hit, stack)) {
            return Result.of(Status.OUT_OF_BOUNDS);
        }
        final ServerLevel hostLevel = (ServerLevel) player.level();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(hostLevel);
        final boolean projecting = physics != null
                && physics.beginAuxiliaryAudioProjection(mapped.structure());
        // Block placement derives its orientation from the player's view and
        // horizontal facing. The auxiliary level is structure-local, so expose
        // the gravity-adjusted look and yaw in that frame for the duration of
        // the call. The real rotation is restored before the server tick sends
        // position packets, so the player never sees a camera snap.
        final LookRestore look = stack.getItem() instanceof BlockItem
                ? LookRestore.apply(player, mapped.structure()) : null;
        try {
            final InteractionResult interaction = player.gameMode.useItemOn(
                    player, mapped.live().level(), stack, hand, mapped.auxiliaryHit());
            return new Result(Status.HANDLED, interaction);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Void-level useItemOn failed for structure body #{}",
                    mapped.structure().body().id(), t);
            return Result.of(Status.FAILED);
        } finally {
            if (look != null) {
                look.restore(player);
            }
            if (projecting) {
                physics.endAuxiliaryAudioProjection();
            }
            finish(player, hostLevel);
        }
    }

    /**
     * Invokes {@link BlockState#useWithoutItem} in the void level. Use this when
     * the caller has already established that no held item should run.
     */
    public static Result useWithoutItem(ServerPlayer player, StructureBlockHitResult hit) {
        final Mapped mapped = map(player, hit);
        if (mapped == null) {
            return Result.of(enabled() ? Status.NOT_HANDLED : Status.DISABLED);
        }
        final ServerLevel hostLevel = (ServerLevel) player.level();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(hostLevel);
        final boolean projecting = physics != null
                && physics.beginAuxiliaryAudioProjection(mapped.structure());
        try {
            final InteractionResult interaction = mapped.live().state().useWithoutItem(
                    mapped.live().level(), player, mapped.auxiliaryHit());
            return new Result(Status.HANDLED, interaction);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Void-level useWithoutItem failed for structure body #{}",
                    mapped.structure().body().id(), t);
            return Result.of(Status.FAILED);
        } finally {
            if (projecting) {
                physics.endAuxiliaryAudioProjection();
            }
            finish(player, hostLevel);
        }
    }

    /**
     * Runs vanilla {@link ServerPlayerGameMode#destroyBlock} against the
     * auxiliary level. The player's game mode is temporarily pointed at the
     * void level for this call and restored immediately afterwards. Drops, XP
     * orbs and block feedback stay in the void only long enough for the
     * projection pass; block changes are mirrored into the structure volume.
     */
    public static Result breakBlock(ServerPlayer player, StructureBlockHitResult hit) {
        final Mapped mapped = map(player, hit);
        if (mapped == null) {
            return Result.of(enabled() ? Status.NOT_HANDLED : Status.DISABLED);
        }
        return breakMapped(player, mapped);
    }

    /**
     * Runs vanilla {@link ServerPlayerGameMode#destroyBlock} for an already
     * mapped session target. The mining state machine uses this so a completed
     * long-press cannot re-map a different block between validation and break.
     */
    public static Result breakMapped(ServerPlayer player, Mapped mapped) {
        if (!enabled() || player == null || mapped == null) {
            return Result.of(enabled() ? Status.NOT_HANDLED : Status.DISABLED);
        }
        final ServerLevel hostLevel = (ServerLevel) player.level();
        final ServerPlayerGameMode gameMode = player.gameMode;
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(hostLevel);
        final boolean projecting = physics != null
                && physics.beginAuxiliaryAudioProjection(mapped.structure());
        try {
            gameMode.setLevel(mapped.live().level());
            final boolean destroyed = gameMode.destroyBlock(mapped.live().pos());
            return new Result(destroyed ? Status.HANDLED : Status.FAILED,
                    destroyed ? InteractionResult.SUCCESS : InteractionResult.FAIL);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Void-level destroyBlock failed for structure body #{}",
                    mapped.structure().body().id(), t);
            return Result.of(Status.FAILED);
        } finally {
            gameMode.setLevel(hostLevel);
            if (projecting) {
                physics.endAuxiliaryAudioProjection();
            }
            finish(player, hostLevel);
        }
    }

    /**
     * Builds the auxiliary-level hit result without touching a level. The
     * structure-local hit fraction and face are preserved, which matters for
     * blocks such as bells that inspect the clicked height.
     */
    public static BlockHitResult auxiliaryHit(StructureBlockHitResult hit, BlockPos auxiliaryPos) {
        final Transform3d pose = hit.structurePose();
        final double scale = hit.blockScale() > 1.0e-9 ? hit.blockScale() : 1.0;
        // Raw (corner-based) structure-local voxel coordinates. Do not subtract
        // 0.5: the hit point is on a block face, and the auxiliary hit needs a
        // fraction in [0, 1] relative to that voxel's corner.
        final Vector3d voxel = pose.inverseTransformPoint(hit.worldHit())
                .mul(1.0 / scale);
        final double localX = clampFraction(voxel.x - hit.voxelX());
        final double localY = clampFraction(voxel.y - hit.voxelY());
        final double localZ = clampFraction(voxel.z - hit.voxelZ());
        return new BlockHitResult(new Vec3(
                auxiliaryPos.getX() + localX,
                auxiliaryPos.getY() + localY,
                auxiliaryPos.getZ() + localZ),
                hit.localFace(), auxiliaryPos, false);
    }

    private static boolean placementTargetInsideVolume(Mapped mapped,
                                                        StructureBlockHitResult hit,
                                                        ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return true;
        }
        final var bounds = mapped.structure().volume().bounds();
        final int adjacentX = hit.adjacentX();
        final int adjacentY = hit.adjacentY();
        final int adjacentZ = hit.adjacentZ();
        if (bounds.contains(adjacentX, adjacentY, adjacentZ)) {
            return true;
        }
        // Replaceable blocks (tall grass, snow layer, ...) are targeted at the
        // clicked voxel rather than the adjacent cell.
        return bounds.contains(hit.voxelX(), hit.voxelY(), hit.voxelZ())
                && mapped.live().state().canBeReplaced();
    }

    /**
     * Mirrors and projects the consequences of an operation that was driven
     * through vanilla game-mode calls outside {@link #useOn}/
     * {@link #breakMapped} (for example a mining session's vanilla
     * {@code handleBlockBreakAction}). Safe to call when nothing changed.
     */
    public static void finishOperation(ServerPlayer player) {
        if (player != null && player.level() instanceof ServerLevel hostLevel) {
            finish(player, hostLevel);
        }
    }

    private static void finish(ServerPlayer player, ServerLevel hostLevel) {
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(hostLevel);
        if (physics == null) {
            return;
        }
        try {
            // This mirrors the changed volume, reindexes/refreshes/splits and
            // re-discovers actors.
            physics.flushAuxiliaryBlockStates();
            // Placing/using a block entity (chest, furnace, ...) can create NBT
            // that has no state change of its own. Mirror it in the same pass
            // instead of waiting for the periodic block-entity mirror.
            final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
            if (auxiliary != null) {
                auxiliary.mirrorBlockEntities();
            }
            // Break/use may have spawned drops, XP orbs or TNT in the void.
            physics.projectAuxiliaryEntities();
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Could not finish redirecting a void-level operation for {}",
                    player.getUUID(), t);
        }
    }

    private static double clampFraction(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Temporarily exposes the player's gravity-adjusted look in the structure's
     * local frame, so vanilla {@code BlockPlaceContext} chooses the same stair /
     * log / slab orientation a player standing in the structure would choose.
     */
    private record LookRestore(float yaw, float pitch, float yawO, float pitchO) {
        static LookRestore apply(ServerPlayer player, PhysicsStructure structure) {
            final Vector3d world = PlayerGravity.lookDirection(player);
            if (world.lengthSquared() < 1.0e-12) {
                return new LookRestore(player.getYRot(), player.getXRot(),
                        player.yRotO, player.xRotO);
            }
            final Vector3d local = structure.pose().inverseTransformDirection(world).normalize();
            final float yaw = io.mikaple.crythst.integration.minecraft.interaction
                    .StructurePlacementMath.lookYaw(local);
            final float pitch = io.mikaple.crythst.integration.minecraft.interaction
                    .StructurePlacementMath.lookPitch(local);
            final LookRestore restore = new LookRestore(
                    player.getYRot(), player.getXRot(), player.yRotO, player.xRotO);
            player.setYRot(yaw);
            player.setXRot(pitch);
            // Make partial-tick look sampling return the same local direction.
            player.yRotO = yaw;
            player.xRotO = pitch;
            return restore;
        }

        void restore(ServerPlayer player) {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
    }
}
