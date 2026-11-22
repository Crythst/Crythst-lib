package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.operation.StructureOperationRedirector;
import io.mikaple.crythst.integration.minecraft.operation.VoidMiningSession;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-authoritative long-press mining against structure voxels.
 *
 * <p>Each player has at most one session. A client START raycasts again,
 * checks reach/occlusion and maps the voxel into the auxiliary void level.
 * Every server tick the session advances vanilla destroy progress
 * ({@code BlockState#getDestroyProgress}, tool/enchantment/effect rules).
 * When progress reaches 1.0 it invokes vanilla
 * {@code ServerPlayerGameMode#destroyBlock} against the auxiliary level:
 * drops, XP, statistics and block rules come from vanilla. A final
 * mirror/projection pass moves block changes and spawned entities back to the
 * host world.</p>
 *
 * <p>Vanilla {@code handleBlockBreakAction} is deliberately not used for the
 * start/stop transitions: it requires the player to be within interaction
 * range of the target {@code BlockPos}, but the target is an auxiliary-region
 * coordinate far from the player. Calling it would silently reject every
 * session and leave long-press mining inert. Destroy progress, tool rules and
 * completion are still the vanilla ones; only the range gate is bypassed by
 * calling {@code destroyBlock} directly on the validated target.</p>
 *
 * <p>Client TICK packets are heartbeats, not progress claims. The client may
 * disconnect or release early; {@link VoidMiningSession#HEARTBEAT_TIMEOUT_TICKS}
 * stops the session if heartbeats stop. Creative mode completes through the
 * redirector's immediate vanilla {@code destroyBlock} on START.</p>
 */
public final class StructureMiningSessions {
    /** Vanilla creative destroy cooldown between blocks. */
    public static final int CREATIVE_BREAK_COOLDOWN_TICKS = 5;
    private static final Map<ServerPlayer, Active> ACTIVE = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_CREATIVE_BREAK = new WeakHashMap<>();
    private static final Map<ServerPlayer, PendingCreative> PENDING_CREATIVE = new WeakHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureMiningSessions() {
    }

    /** Registers the per-server-tick progress pass. Safe to call repeatedly. */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(StructureMiningSessions::tickAll);
    }

    /**
     * Starts or refreshes a mining session.
     *
     * @return {@code true} when vanilla/redirect handling owns the click;
     *         {@code false} when the caller should use the legacy custom break
     *         fallback (redirect disabled or auxiliary block unavailable)
     */
    public static boolean start(ServerPlayer player, StructureBlockHitResult hit) {
        if (!StructureOperationRedirector.enabled()
                || player == null || hit == null || hit.structure() == null
                || player.isSpectator()) {
            return false;
        }
        final boolean creative = player.gameMode().isCreative();
        if (Boolean.getBoolean("crythst.mining.debug")) {
            Crythst.LOGGER.info("Crythst mining START player={} mode={} creative={} target={},{},{}",
                    player.getGameProfile().name(), player.gameMode(),
                    creative, hit.voxelX(), hit.voxelY(), hit.voxelZ());
        }
        // The dedicated game mode starts in SURVIVAL. Creative uses the
        // redirector's immediate destroy. Adventure must not get a session:
        // falling back lets the real player game mode apply its restrictions.
        if (!creative && !player.gameMode().isSurvival()) {
            return false;
        }
        final StructureOperationRedirector.Mapped mapped =
                StructureOperationRedirector.map(player, hit);

        final PhysicsStructure structure = hit.structure();
        final VoidMiningSession.Target target = target(hit);
        final Item tool = player.getMainHandItem().getItem();
        final long now = player.level().getGameTime();

        final Active existing = ACTIVE.get(player);
        if (existing != null) {
            final VoidMiningSession.Step step =
                    existing.session.start(now, target, tool, creative);
            if (step == VoidMiningSession.Step.NONE) {
                // Duplicate START from the attack callback plus the payload.
                existing.hit = hit;
                return true;
            }
            ACTIVE.remove(player);
            if (step == VoidMiningSession.Step.BREAK) {
                return creativeBreak(player, mapped, hit, now);
            }
            // STARTED means the target/tool changed; fall through to a fresh
            // session. ABORTED/other states also simply restart.
        }

        final VoidMiningSession session = new VoidMiningSession();
        final VoidMiningSession.Step step = session.start(now, target, tool, creative);
        if (step == VoidMiningSession.Step.BREAK) {
            return creativeBreak(player, mapped, hit, now);
        }
        if (step != VoidMiningSession.Step.STARTED) {
            return false;
        }

        if (mapped == null) {
            // The auxiliary block is unavailable, but the structure state is
            // still usable. Keep the same held-progress semantics and break
            // through the custom editable-volume path instead of breaking on
            // the first click.
            ACTIVE.put(player, new Active(structure, hit, session, tool,
                    hit.blockState(), true));
            if (Boolean.getBoolean("crythst.mining.debug")) {
                Crythst.LOGGER.info("Crythst mining session fallback (aux block unavailable)");
            }
            return true;
        }

        final LevelPhysics physics = physicsOf(player);
        final EmptyPhysicsLevel.LiveBlock after = physics == null ? null
                : physics.liveBlock(structure, hit.voxelX(), hit.voxelY(), hit.voxelZ());
        if (after == null || after.state().isAir()) {
            session.abort();
            StructureOperationRedirector.finishOperation(player);
            return true;
        }

        ACTIVE.put(player, new Active(structure, hit, session, tool, after.state(), false));
        return true;
    }

    /**
     * Refreshes the heartbeat for a client TICK packet. The optional fresh hit
     * updates the stored world point when it still describes the same
     * body/voxel; a miss or a different target does not cancel the session,
     * because the server tick validates reach/occlusion independently and a
     * new target is selected through START.
     */
    public static boolean heartbeat(ServerPlayer player, @Nullable StructureBlockHitResult hit) {
        final Active active = ACTIVE.get(player);
        if (active == null || !active.session.active()) {
            return false;
        }
        if (hit != null && (hit.structure() != active.structure
                || !target(hit).equals(active.session.target()))) {
            // A new target is selected through START, not TICK. Ignore a
            // mismatched optional raycast instead of killing a still valid
            // session during server/client look latency.
            hit = null;
        }
        final long now = player.level().getGameTime();
        final VoidMiningSession.Target target = active.session.target();
        final VoidMiningSession.Step step = active.session.heartbeat(
                now, target, player.getMainHandItem().getItem());
        if (step == VoidMiningSession.Step.ABORTED) {
            ACTIVE.remove(player);
            return false;
        }
        if (hit != null) {
            active.hit = hit;
        }
        return true;
    }

    /** Stops and removes the player's session, if any. */
    public static void stop(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PENDING_CREATIVE.remove(player);
        final Active active = ACTIVE.remove(player);
        if (active != null) {
            active.session.stop();
        }
    }

    /**
     * Queues or performs a creative break. Vanilla creative has a five-tick
     * destroy cooldown; without it a held attack would remove one structure
     * voxel per tick as the crosshair advances.
     */
    private static boolean creativeBreak(ServerPlayer player,
                                         @Nullable StructureOperationRedirector.Mapped mapped,
                                         StructureBlockHitResult hit, long now) {
        final Long last = LAST_CREATIVE_BREAK.get(player);
        if (last != null && now - last < CREATIVE_BREAK_COOLDOWN_TICKS) {
            PENDING_CREATIVE.put(player, new PendingCreative(
                    hit, last + CREATIVE_BREAK_COOLDOWN_TICKS));
            return true;
        }
        LAST_CREATIVE_BREAK.put(player, now);
        PENDING_CREATIVE.remove(player);
        breakImmediately(player, mapped, hit);
        return true;
    }

    private static void tickPendingCreative() {
        if (PENDING_CREATIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<ServerPlayer, PendingCreative> entry
                : List.copyOf(PENDING_CREATIVE.entrySet())) {
            final ServerPlayer player = entry.getKey();
            final PendingCreative pending = entry.getValue();
            if (player == null || player.hasDisconnected() || player.isRemoved()
                    || player.isSpectator()
                    || !(player.level() instanceof ServerLevel host)) {
                PENDING_CREATIVE.remove(player);
                continue;
            }
            final long now = host.getGameTime();
            if (now < pending.dueTick()) {
                continue;
            }
            PENDING_CREATIVE.remove(player);
            final StructureBlockHitResult hit = pending.hit();
            if (hit.structure() == null || hit.structure().closed()
                    || player.getEyePosition().distanceToSqr(hit.getLocation())
                            > player.blockInteractionRange() * player.blockInteractionRange()
                                    + 1.0e-6
                    || !StructureInteraction.isStructureInFrontOfRealBlocks(player, hit)) {
                continue;
            }
            LAST_CREATIVE_BREAK.put(player, now);
            breakImmediately(player, StructureOperationRedirector.map(player, hit), hit);
        }
    }

    private record PendingCreative(StructureBlockHitResult hit, long dueTick) {
    }

    private static void tickAll(MinecraftServer server) {
        tickPendingCreative();
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<ServerPlayer, Active> entry : List.copyOf(ACTIVE.entrySet())) {
            final ServerPlayer player = entry.getKey();
            final Active active = entry.getValue();
            if (player == null || player.hasDisconnected() || player.isRemoved()
                    || player.isSpectator()
                    || !(player.level() instanceof ServerLevel host)) {
                ACTIVE.remove(player);
                continue;
            }
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(host);
            if (physics == null || active.structure.closed()) {
                ACTIVE.remove(player);
                continue;
            }
            final VoidMiningSession.Target target = active.session.target();
            if (target == null) {
                ACTIVE.remove(player);
                continue;
            }
            final EmptyPhysicsLevel.LiveBlock live = active.fallback ? null
                    : physics.liveBlock(active.structure, target.x(), target.y(), target.z());
            if (!active.fallback && (live == null || live.state() != active.expectedState)) {
                // Another edit removed/changed the voxel, or the auxiliary
                // chunk unloaded. Never guess: drop this session.
                ACTIVE.remove(player);
                continue;
            }
            if (!stillValid(player, active, live)) {
                ACTIVE.remove(player);
                continue;
            }

            final long now = host.getGameTime();
            final VoidMiningSession.Step checked = active.session.checkHeartbeat(now);
            if (checked == VoidMiningSession.Step.EXPIRED) {
                ACTIVE.remove(player);
                continue;
            }

            // Advance server-owned vanilla progress. The per-tick value is the
            // same BlockState#getDestroyProgress the vanilla game mode uses.
            final float perTick = active.fallback
                    ? active.expectedState.getDestroyProgress(
                            player, player.level(), active.hit.getBlockPos())
                    : live.state().getDestroyProgress(player, live.level(), live.pos());
            final VoidMiningSession.Step step = active.session.tick(now, perTick);
            if (step == VoidMiningSession.Step.BREAK) {
                ACTIVE.remove(player);
                if (Boolean.getBoolean("crythst.mining.debug")) {
                    Crythst.LOGGER.info("Crythst mining BREAK elapsedTicks={} perTick={} fallback={}",
                            now - active.session.startTick(), perTick, active.fallback);
                }
                if (active.fallback) {
                    breakCompletedFallback(player, active);
                } else {
                    breakCompleted(player, active, live);
                }
            } else if (step == VoidMiningSession.Step.EXPIRED
                    || step == VoidMiningSession.Step.ABORTED) {
                ACTIVE.remove(player);
            }
        }
    }

    private static boolean stillValid(ServerPlayer player, Active active,
                                      @Nullable EmptyPhysicsLevel.LiveBlock live) {
        final Vec3 eye = player.getEyePosition();
        final double range = player.blockInteractionRange();
        if (eye.distanceToSqr(active.hit.getLocation()) > range * range + 1.0e-6) {
            return false;
        }
        if (!StructureInteraction.isStructureInFrontOfRealBlocks(player, active.hit)) {
            return false;
        }
        if (player.getMainHandItem().getItem() != active.tool) {
            return false;
        }
        if (!player.gameMode().isSurvival()) {
            // The session only runs for survival; never let a mode change
            // bypass adventure/creative restrictions.
            return false;
        }
        return active.fallback || (live != null && !live.state().isAir());
    }

    private static void breakCompleted(ServerPlayer player, Active active,
                                       EmptyPhysicsLevel.LiveBlock live) {
        // One break per player per tick. A duplicate session or a competing
        // custom fallback cannot break twice.
        if (!StructureInteraction.beginMine(player)) {
            return;
        }
        final StructureOperationRedirector.Mapped mapped =
                new StructureOperationRedirector.Mapped(
                        active.structure, live,
                        StructureOperationRedirector.auxiliaryHit(active.hit, live.pos()));
        // breakMapped runs vanilla destroyBlock in the auxiliary level (drops,
        // XP, statistics, durability and tool rules) and then mirrors/projects
        // the consequences back to the host world.
        StructureOperationRedirector.breakMapped(player, mapped);
    }

    private static void breakCompletedFallback(ServerPlayer player, Active active) {
        if (!StructureInteraction.beginMine(player)) {
            return;
        }
        StructureInteraction.breakBlock(player, active.hit);
    }

    private static void breakImmediately(ServerPlayer player,
                                         @Nullable StructureOperationRedirector.Mapped mapped,
                                         StructureBlockHitResult hit) {
        if (!StructureInteraction.beginMine(player)) {
            return;
        }
        if (mapped == null) {
            StructureInteraction.breakBlock(player, hit);
            return;
        }
        StructureOperationRedirector.breakMapped(player, mapped);
    }

    private static LevelPhysics physicsOf(ServerPlayer player) {
        return MinecraftPhysics.getIfPresent((ServerLevel) player.level());
    }

    private static VoidMiningSession.Target target(StructureBlockHitResult hit) {
        return new VoidMiningSession.Target(
                hit.bodyId(), hit.voxelX(), hit.voxelY(), hit.voxelZ());
    }

    /** Mutable per-player session wrapper; only touched on the server thread. */
    private static final class Active {
        private final PhysicsStructure structure;
        private StructureBlockHitResult hit;
        private final VoidMiningSession session;
        private final Item tool;
        private final BlockState expectedState;
        private final boolean fallback;

        private Active(PhysicsStructure structure, StructureBlockHitResult hit,
                       VoidMiningSession session, Item tool, BlockState expectedState,
                       boolean fallback) {
            this.structure = structure;
            this.hit = hit;
            this.session = session;
            this.tool = tool;
            this.expectedState = expectedState;
            this.fallback = fallback;
        }
    }
}
